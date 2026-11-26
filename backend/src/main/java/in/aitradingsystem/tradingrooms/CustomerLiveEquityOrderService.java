package in.aitradingsystem.tradingrooms;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Customer-scoped read side for the isolated LIVE intraday equity ledger. */
@Service
public class CustomerLiveEquityOrderService {
    private final JdbcClient jdbc;
    private final CustomerExecutionReadinessService readiness;

    public CustomerLiveEquityOrderService(JdbcClient jdbc,
            CustomerExecutionReadinessService readiness) {
        this.jdbc = jdbc;
        this.readiness = readiness;
    }

    public record Order(UUID id, UUID roomId, String broker, long instrumentId,
            String tradingSymbol, String exchange, String side, int quantity,
            BigDecimal limitPrice, String state, String brokerOrderId,
            String outcomeCode, Instant createdAt, Instant updatedAt) {}

    private record StoredOrder(Order order, String payloadHash) {}
    private record ClaimRoom(String mode, String marketScope, long plannedBudgetInr,
                             String selectedBroker) {}

    /**
     * Reserve a single LIVE cash-equity order after all preflight gates pass.
     * The customer row lock serializes reservations across every room for the
     * same broker owner. This method never contacts a broker; its caller may
     * dispatch once, only after this transaction commits.
     */
    @Transactional
    public Order claimForSubmission(UUID owner, UUID roomId, String idempotencyKey,
            CustomerExecutionReadinessService.Preflight proposal) {
        if (owner == null || roomId == null || proposal == null
                || !roomId.equals(proposal.roomId())
                || idempotencyKey == null
                || !idempotencyKey.matches("[A-Za-z0-9_-]{8,64}")
                || proposal.instrumentId() <= 0
                || !"LIVE".equals(proposal.mode())
                || proposal.selectedBroker() == null
                || !("ANGEL_ONE".equals(proposal.selectedBroker())
                    || "UPSTOX".equals(proposal.selectedBroker()))
                || !("NSE".equals(proposal.exchange()) || "BSE".equals(proposal.exchange()))
                || !("BUY".equals(proposal.side()) || "SELL".equals(proposal.side()))
                || proposal.quantity() < 1 || proposal.quantity() > 10000
                || proposal.limitPrice() == null || proposal.limitPrice().signum() <= 0
                || proposal.limitPrice().scale() > 2
                || proposal.limitPrice().compareTo(new BigDecimal("10000000")) > 0
                || proposal.blockers() == null || proposal.tradingSymbol() == null
                || proposal.tradingSymbol().isBlank() || proposal.tradingSymbol().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid LIVE intraday equity order claim");
        }
        String fingerprint = fingerprint(owner, roomId, proposal);
        jdbc.sql("SELECT id FROM app_user WHERE id=:owner FOR UPDATE")
            .param("owner", owner).query(UUID.class).optional()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Customer account not found"));

        StoredOrder prior = byIdempotencyKey(owner, roomId, idempotencyKey);
        if (prior != null) {
            if (!MessageDigest.isEqual(prior.payloadHash().getBytes(StandardCharsets.US_ASCII),
                    fingerprint.getBytes(StandardCharsets.US_ASCII))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key was already used for a different order");
            }
            return prior.order();
        }
        if (!proposal.executable() || !proposal.blockers().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "LIVE intraday equity preflight is not executable");
        }

        ClaimRoom room = jdbc.sql("""
                SELECT r.mode,r.market_scope,r.planned_budget_inr,b.broker
                FROM customer_trading_room r
                LEFT JOIN customer_live_room_broker b
                  ON b.room_id=r.id AND b.user_id=r.user_id AND b.mode='LIVE'
                WHERE r.id=:room AND r.user_id=:owner
                FOR UPDATE OF r
                """)
            .param("room", roomId).param("owner", owner)
            .query((rs,n) -> new ClaimRoom(rs.getString("mode"),
                rs.getString("market_scope"),rs.getLong("planned_budget_inr"),
                rs.getString("broker")))
            .optional().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Trading Room not found"));
        if (!"LIVE".equals(room.mode()) || "FNO".equals(room.marketScope())
                || !Objects.equals(room.selectedBroker(), proposal.selectedBroker())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "LIVE room or selected broker changed during order preflight");
        }
        Boolean halted = jdbc.sql("SELECT new_orders_halted FROM trading_control WHERE id=1 FOR SHARE")
            .query(Boolean.class).optional().orElse(null);
        if (!Boolean.FALSE.equals(halted)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Global halt is active or unavailable");
        }
        Long pending = jdbc.sql("""
                SELECT COUNT(*) FROM customer_live_equity_order
                WHERE user_id=:owner
                  AND state IN ('SUBMITTING','UNKNOWN','ACKNOWLEDGED','PARTIALLY_FILLED')
                """)
            .param("owner", owner).query(Long.class).single();
        if (pending != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "An existing LIVE equity order requires reconciliation");
        }
        BigDecimal committed = todayRoomBuyNotional(owner, roomId);
        BigDecimal budget = BigDecimal.valueOf(room.plannedBudgetInr());
        BigDecimal notional = proposal.limitPrice().multiply(
            BigDecimal.valueOf(proposal.quantity()));
        if (committed == null || ("BUY".equals(proposal.side())
                && committed.add(notional).compareTo(budget) > 0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Room intraday exposure is unavailable or exceeds its budget");
        }
        if (proposal.roomBudget() == null || proposal.roomBudget().compareTo(budget) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Room budget changed during order preflight");
        }

        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer_live_equity_order
                    (id,room_id,user_id,mode,broker,instrument_id,trading_symbol,
                     exchange,side,quantity,limit_price,idempotency_key,payload_hash,
                     state,outcome_code)
                VALUES (:id,:room,:owner,'LIVE',:broker,:instrument,:symbol,
                        :exchange,:side,:quantity,:price,:idempotency,:hash,
                        'SUBMITTING','LIVE_EQUITY_ORDER_CLAIMED')
                """)
            .param("id", orderId).param("room", roomId).param("owner", owner)
            .param("broker", proposal.selectedBroker()).param("instrument", proposal.instrumentId())
            .param("symbol", proposal.tradingSymbol()).param("exchange", proposal.exchange())
            .param("side", proposal.side()).param("quantity", proposal.quantity())
            .param("price", proposal.limitPrice()).param("idempotency", idempotencyKey)
            .param("hash", fingerprint).update();
        return byIdempotencyKey(owner, roomId, idempotencyKey).order();
    }

    @Transactional(readOnly = true)
    public List<Order> list(UUID owner, UUID roomId) {
        // First prove this is the caller's room; a foreign room returns 404 even if empty.
        readiness.readiness(owner, roomId);
        return jdbc.sql("""
            SELECT id,room_id,broker,instrument_id,trading_symbol,exchange,side,
                   quantity,limit_price,state,broker_order_id,outcome_code,
                   created_at,updated_at
            FROM customer_live_equity_order
            WHERE user_id=:owner AND room_id=:room
            ORDER BY created_at DESC,id DESC LIMIT 100
            """)
            .param("owner", owner).param("room", roomId)
            .query((rs,n) -> mapOrder(rs))
            .list();
    }

    private StoredOrder byIdempotencyKey(UUID owner, UUID roomId, String key) {
        return jdbc.sql("""
                SELECT id,room_id,broker,instrument_id,trading_symbol,exchange,side,
                       quantity,limit_price,state,broker_order_id,outcome_code,
                       created_at,updated_at,payload_hash
                FROM customer_live_equity_order
                WHERE user_id=:owner AND room_id=:room AND idempotency_key=:key
                """)
            .param("owner", owner).param("room", roomId).param("key", key)
            .query((rs,n) -> new StoredOrder(mapOrder(rs),rs.getString("payload_hash")))
            .optional().orElse(null);
    }

    private BigDecimal todayRoomBuyNotional(UUID owner, UUID roomId) {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        LocalDate today = LocalDate.now(zone);
        return jdbc.sql("""
                SELECT COALESCE(SUM(limit_price * quantity),0)
                FROM customer_live_equity_order
                WHERE user_id=:owner AND room_id=:room AND side='BUY'
                  AND state <> 'REJECTED'
                  AND created_at >= :start AND created_at < :end
                """)
            .param("owner", owner).param("room", roomId)
            .param("start", today.atStartOfDay(zone).toInstant())
            .param("end", today.plusDays(1).atStartOfDay(zone).toInstant())
            .query(BigDecimal.class).single();
    }

    private static String fingerprint(UUID owner, UUID room,
            CustomerExecutionReadinessService.Preflight proposal) {
        String canonical = String.join("|", owner.toString(), room.toString(),
            proposal.selectedBroker(), Long.toString(proposal.instrumentId()),
            proposal.tradingSymbol(), proposal.exchange(), proposal.side(),
            Integer.toString(proposal.quantity()), proposal.limitPrice().setScale(2).toPlainString(),
            "INTRADAY", "LIMIT", "DAY");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Order mapOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Order(rs.getObject("id",UUID.class),rs.getObject("room_id",UUID.class),
            rs.getString("broker"),rs.getLong("instrument_id"),rs.getString("trading_symbol"),
            rs.getString("exchange"),rs.getString("side"),rs.getInt("quantity"),
            rs.getBigDecimal("limit_price"),rs.getString("state"),rs.getString("broker_order_id"),
            rs.getString("outcome_code"),rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * Apply a broker acknowledgement/reconciliation observation without ever
     * resubmitting. The caller must supply the state it observed; row locking
     * plus the transition graph makes duplicate or stale reconciliation fail
     * closed instead of overwriting newer broker evidence.
     */
    @Transactional
    public Order recordBrokerObservation(UUID owner, UUID roomId, UUID orderId,
            LiveEquityOrderState expected, LiveEquityOrderState observed,
            String brokerOrderId, String outcomeCode) {
        if (expected == null || observed == null || outcomeCode == null
                || !outcomeCode.matches("[A-Z0-9_:-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid broker observation");
        }
        readiness.readiness(owner, roomId);
        var current = jdbc.sql("""
                SELECT state,broker_order_id FROM customer_live_equity_order
                WHERE id=:id AND user_id=:owner AND room_id=:room
                FOR UPDATE
                """)
            .param("id", orderId).param("owner", owner).param("room", roomId)
            .query((rs, row) -> new Current(rs.getString("state"),
                rs.getString("broker_order_id")))
            .optional().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "LIVE equity order not found"));
        LiveEquityOrderState currentState;
        try {
            currentState = LiveEquityOrderState.valueOf(current.state());
        } catch (IllegalArgumentException invalidStoredState) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Stored order state requires operator review");
        }
        if (currentState != expected || !currentState.mayTransitionTo(observed)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Stale or disallowed order state transition");
        }
        if (current.brokerOrderId() != null && brokerOrderId != null
                && !Objects.equals(current.brokerOrderId(), brokerOrderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Broker order identity changed during reconciliation");
        }
        if (brokerOrderId != null && !brokerOrderId.matches("[A-Za-z0-9._:-]{1,100}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid broker order identity");
        }
        int updated = jdbc.sql("""
                UPDATE customer_live_equity_order
                SET state=:observed, broker_order_id=COALESCE(:brokerOrderId,broker_order_id),
                    outcome_code=:outcomeCode, updated_at=NOW()
                WHERE id=:id AND user_id=:owner AND room_id=:room AND state=:expected
                """)
            .param("observed", observed.name())
            .param("brokerOrderId", brokerOrderId)
            .param("outcomeCode", outcomeCode)
            .param("id", orderId).param("owner", owner).param("room", roomId)
            .param("expected", expected.name()).update();
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Order changed during reconciliation");
        }
        return jdbc.sql("""
                SELECT id,room_id,broker,instrument_id,trading_symbol,exchange,side,
                       quantity,limit_price,state,broker_order_id,outcome_code,
                       created_at,updated_at
                FROM customer_live_equity_order
                WHERE id=:id AND user_id=:owner AND room_id=:room
                """)
            .param("id", orderId).param("owner", owner).param("room", roomId)
            .query((rs,n) -> new Order(rs.getObject("id",UUID.class),
                rs.getObject("room_id",UUID.class),rs.getString("broker"),
                rs.getLong("instrument_id"),rs.getString("trading_symbol"),
                rs.getString("exchange"),rs.getString("side"),rs.getInt("quantity"),
                rs.getBigDecimal("limit_price"),rs.getString("state"),
                rs.getString("broker_order_id"),rs.getString("outcome_code"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()))
            .single();
    }

    private record Current(String state, String brokerOrderId) {}
}
