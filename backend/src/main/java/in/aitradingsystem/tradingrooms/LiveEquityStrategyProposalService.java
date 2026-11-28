package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.mind.MindPaperCycleService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Converts one fresh Mind PAPER strategy result into a short-lived LIVE order
 * proposal. Submission always delegates to the existing LIVE coordinator;
 * this class has no broker transport and cannot bypass its consent or risk gates.
 */
@Service
public class LiveEquityStrategyProposalService {
    static final Duration MAX_SIGNAL_AGE = Duration.ofMinutes(2);
    private static final Set<String> STRATEGIES = Set.of(
        "SMA_TREND_VOLUME_V1", "OPENING_RANGE_BREAKOUT_V1", "VWAP_MOMENTUM_V1",
        "MA_RECLAIM_V1", "VOLUME_BREAKOUT_V1", "RSI_REVERSAL_V1");

    private static final String SELECT = """
        SELECT proposal_id,user_id,live_room_id,source_paper_room_id,source_cycle_id,
               strategy_code,instrument_id,trading_symbol,side,quantity,limit_price,
               stop_price,target_price,signal_id,candle_at,quote_exchange_at,
               created_at,expires_at,live_order_id
          FROM customer_live_equity_strategy_proposal
        """;

    private final JdbcClient jdbc;
    private final MindPaperCycleService cycles;
    private final LiveEquityOrderCoordinator coordinator;
    private final Clock clock;

    public LiveEquityStrategyProposalService(JdbcClient jdbc, MindPaperCycleService cycles,
            LiveEquityOrderCoordinator coordinator, Clock clock) {
        this.jdbc = jdbc;
        this.cycles = cycles;
        this.coordinator = coordinator;
        this.clock = clock;
    }

    public record Proposal(UUID id, UUID liveRoomId, UUID sourcePaperRoomId, UUID sourceCycleId,
            String strategyCode, long instrumentId, String tradingSymbol, String side, int quantity,
            BigDecimal limitPrice, BigDecimal stopPrice, BigDecimal targetPrice, String signalId,
            Instant candleAt, Instant quoteExchangeAt, Instant createdAt, Instant expiresAt,
            UUID liveOrderId, String status) {}

    public record Submission(Proposal proposal, CustomerLiveEquityOrderService.Order order,
            boolean created) {}

    /**
     * A source cycle can be handed to at most one LIVE room for this owner. Repeating
     * this call returns the same durable proposal; it never creates an order.
     */
    @Transactional
    public Proposal create(UUID owner, UUID liveRoomId, UUID sourcePaperRoomId, UUID sourceCycleId) {
        if (owner == null || liveRoomId == null || sourcePaperRoomId == null || sourceCycleId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid strategy proposal request");
        }
        requireIntradayLiveRoom(owner, liveRoomId);
        requireIntradayPaperRoom(owner, sourcePaperRoomId);
        MindPaperCycleService.Cycle cycle = cycles.recent(owner, sourcePaperRoomId).stream()
            .filter(item -> sourceCycleId.equals(item.id()))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Owner's recent PAPER strategy cycle not found"));
        Instant now = clock.instant();
        requireEligibleCycle(cycle, now);

        UUID proposalId = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO customer_live_equity_strategy_proposal
              (proposal_id,user_id,live_room_id,source_paper_room_id,source_cycle_id,
               strategy_code,instrument_id,trading_symbol,side,quantity,limit_price,
               stop_price,target_price,signal_id,candle_at,quote_exchange_at,expires_at)
            VALUES (:id,:owner,:liveRoom,:paperRoom,:cycle,:strategy,:instrument,:symbol,:side,:quantity,
                    :limit,:stop,:target,:signal,:candleAt,:quoteAt,:expires)
            ON CONFLICT (user_id,source_cycle_id) DO NOTHING
            """)
            .param("id", proposalId).param("owner", owner).param("liveRoom", liveRoomId)
            .param("paperRoom", sourcePaperRoomId).param("cycle", sourceCycleId)
            .param("strategy", cycle.strategyCode()).param("instrument", cycle.instrumentId())
            .param("symbol", cycle.tradingSymbol()).param("side", cycle.side())
            .param("quantity", cycle.quantity()).param("limit", cycle.limitPrice())
            .param("stop", cycle.stopPrice()).param("target", cycle.targetPrice())
            .param("signal", cycle.signalId()).param("candleAt", cycle.candleTime())
            .param("quoteAt", cycle.quoteExchangeAt())
            .param("expires", cycle.createdAt().plus(MAX_SIGNAL_AGE)).update();

        Proposal stored = bySourceCycle(owner, sourceCycleId)
            .orElseThrow(() -> new IllegalStateException("Strategy proposal was not persisted"));
        if (!stored.liveRoomId().equals(liveRoomId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This strategy cycle already has a proposal for another LIVE room");
        }
        return stored;
    }

    /**
     * Sends the proposal through the exact same gated coordinator as the manual
     * order ticket. Every submission needs the existing per-order consent phrase.
     * The deterministic coordinator key makes retries safe and non-dispatching.
     */
    public Submission submit(UUID owner, UUID liveRoomId, UUID proposalId, String confirmation) {
        Proposal proposal = byId(owner, liveRoomId, proposalId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "LIVE strategy proposal not found"));
        Instant now = clock.instant();
        if (!now.isBefore(proposal.expiresAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Strategy proposal expired; run a new PAPER cycle");
        }
        if (proposal.liveOrderId() != null) {
            CustomerLiveEquityOrderService.Order prior = jdbc.sql("""
                SELECT id,room_id,broker,instrument_id,trading_symbol,exchange,side,quantity,
                       limit_price,state,broker_order_id,filled_quantity,outcome_code,created_at,updated_at
                  FROM customer_live_equity_order
                 WHERE id=:order AND user_id=:owner AND room_id=:room
                """)
                .param("order", proposal.liveOrderId()).param("owner", owner).param("room", liveRoomId)
                .query((rs, row) -> mapOrder(rs)).optional()
                .orElseThrow(() -> new IllegalStateException("Linked LIVE order was not found"));
            return new Submission(proposal, prior, false);
        }

        String key = "MINDLIVE_" + proposal.id().toString().replace("-", "");
        LiveEquityOrderCoordinator.Dispatch dispatch = coordinator.submit(owner, liveRoomId, key,
            proposal.instrumentId(), proposal.side(), proposal.quantity(), proposal.limitPrice(), confirmation);
        jdbc.sql("""
            UPDATE customer_live_equity_strategy_proposal
               SET live_order_id=:order
             WHERE proposal_id=:proposal AND user_id=:owner AND live_room_id=:room
               AND live_order_id IS NULL
            """)
            .param("order", dispatch.order().id()).param("proposal", proposalId)
            .param("owner", owner).param("room", liveRoomId).update();
        Proposal updated = byId(owner, liveRoomId, proposalId).orElse(proposal);
        return new Submission(updated, dispatch.order(), dispatch.created());
    }

    static boolean eligibleCycle(MindPaperCycleService.Cycle cycle, Instant now) {
        if (cycle == null || now == null || cycle.strategyCode() == null
                || !STRATEGIES.contains(cycle.strategyCode()) || cycle.interval() == null
                || cycle.outcome() != MindPaperCycleService.Outcome.FILLED || cycle.liveOrder()
                || cycle.orderId() == null || cycle.signalId() == null || cycle.signalId().isBlank()
                || cycle.signalId().length() > 100 || cycle.signalAction() == null
                || !cycle.signalAction().equals(cycle.side())
                || !("BUY".equals(cycle.side()) || "SELL".equals(cycle.side()))
                || cycle.quantity() == null || cycle.quantity() < 1
                || cycle.limitPrice() == null || cycle.limitPrice().signum() <= 0
                || cycle.quoteLtp() == null || cycle.quoteLtp().signum() <= 0
                || cycle.stopPrice() == null || cycle.stopPrice().signum() <= 0
                || cycle.targetPrice() == null || cycle.targetPrice().signum() <= 0
                || cycle.candleTime() == null || cycle.quoteExchangeAt() == null
                || cycle.createdAt() == null || cycle.tradingSymbol() == null
                || cycle.tradingSymbol().isBlank() || cycle.dataLabel() == null
                || !cycle.dataLabel().contains("VERIFIED_LIVE_TICK")
                || !allowedIntradayInterval(cycle.strategyCode(), cycle.interval())) {
            return false;
        }
        Duration createdAge = Duration.between(cycle.createdAt(), now);
        Duration quoteAge = Duration.between(cycle.quoteExchangeAt(), now);
        if (createdAge.isNegative() || quoteAge.isNegative()
                || createdAge.compareTo(MAX_SIGNAL_AGE) > 0 || quoteAge.compareTo(MAX_SIGNAL_AGE) > 0) {
            return false;
        }
        BigDecimal price = cycle.limitPrice();
        return "BUY".equals(cycle.side())
            ? cycle.stopPrice().compareTo(price) < 0 && cycle.targetPrice().compareTo(price) > 0
            : cycle.stopPrice().compareTo(price) > 0 && cycle.targetPrice().compareTo(price) < 0;
    }

    private static boolean allowedIntradayInterval(String strategyCode, String interval) {
        if ("SMA_TREND_VOLUME_V1".equals(strategyCode)) {
            return Set.of("FIVE_MINUTE", "FIFTEEN_MINUTE", "ONE_HOUR").contains(interval);
        }
        return Set.of("ONE_MINUTE", "THREE_MINUTE", "FIVE_MINUTE").contains(interval);
    }

    private static void requireEligibleCycle(MindPaperCycleService.Cycle cycle, Instant now) {
        if (!eligibleCycle(cycle, now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "PAPER cycle is not a fresh, filled intraday strategy candidate");
        }
    }

    private void requireIntradayPaperRoom(UUID owner, UUID paperRoomId) {
        record Room(String marketScope, String focus) {}
        Room room = jdbc.sql("""
            SELECT market_scope,focus FROM customer_trading_room
             WHERE id=:room AND user_id=:owner AND mode='PAPER' AND status='PAPER_FUNDED'
            """).param("room", paperRoomId).param("owner", owner)
            .query((rs, row) -> new Room(rs.getString("market_scope"), rs.getString("focus")))
            .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Owner's funded PAPER room not found"));
        if (!("EQUITY".equals(room.marketScope()) || "ALL".equals(room.marketScope()))
                || !"INTRADAY".equals(room.focus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Strategy proposals require an intraday PAPER equity room");
        }
    }

    private void requireIntradayLiveRoom(UUID owner, UUID liveRoomId) {
        record Room(String marketScope, String focus) {}
        Room room = jdbc.sql("""
            SELECT market_scope,focus FROM customer_trading_room
             WHERE id=:room AND user_id=:owner AND mode='LIVE'
            """).param("room", liveRoomId).param("owner", owner)
            .query((rs, row) -> new Room(rs.getString("market_scope"), rs.getString("focus")))
            .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Owner's LIVE room not found"));
        if (!("EQUITY".equals(room.marketScope()) || "ALL".equals(room.marketScope()))
                || !"INTRADAY".equals(room.focus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Strategy proposals require an intraday LIVE equity room");
        }
    }

    private java.util.Optional<Proposal> bySourceCycle(UUID owner, UUID sourceCycleId) {
        return jdbc.sql(SELECT + " WHERE user_id=:owner AND source_cycle_id=:cycle")
            .param("owner", owner).param("cycle", sourceCycleId)
            .query((rs, row) -> mapProposal(rs)).optional();
    }

    private java.util.Optional<Proposal> byId(UUID owner, UUID roomId, UUID proposalId) {
        return jdbc.sql(SELECT + " WHERE user_id=:owner AND live_room_id=:room AND proposal_id=:id")
            .param("owner", owner).param("room", roomId).param("id", proposalId)
            .query((rs, row) -> mapProposal(rs)).optional();
    }

    private Proposal mapProposal(ResultSet rs) throws SQLException {
        Instant now = clock.instant();
        UUID orderId = rs.getObject("live_order_id", UUID.class);
        Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
        String status = orderId != null ? "SUBMITTED" : now.isBefore(expiresAt) ? "PENDING" : "EXPIRED";
        return new Proposal(rs.getObject("proposal_id", UUID.class), rs.getObject("live_room_id", UUID.class),
            rs.getObject("source_paper_room_id", UUID.class), rs.getObject("source_cycle_id", UUID.class),
            rs.getString("strategy_code"), rs.getLong("instrument_id"), rs.getString("trading_symbol"),
            rs.getString("side"), rs.getInt("quantity"), rs.getBigDecimal("limit_price"),
            rs.getBigDecimal("stop_price"), rs.getBigDecimal("target_price"), rs.getString("signal_id"),
            rs.getTimestamp("candle_at").toInstant(), rs.getTimestamp("quote_exchange_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(), expiresAt, orderId, status);
    }

    private static CustomerLiveEquityOrderService.Order mapOrder(ResultSet rs) throws SQLException {
        return new CustomerLiveEquityOrderService.Order(rs.getObject("id", UUID.class),
            rs.getObject("room_id", UUID.class), rs.getString("broker"), rs.getLong("instrument_id"),
            rs.getString("trading_symbol"), rs.getString("exchange"), rs.getString("side"),
            rs.getInt("quantity"), rs.getBigDecimal("limit_price"), rs.getString("state"),
            rs.getString("broker_order_id"), rs.getInt("filled_quantity"), rs.getString("outcome_code"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
