package in.aitradingsystem.tradingrooms;

import com.fasterxml.jackson.databind.JsonNode;
import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
import in.aitradingsystem.provider.upstox.CustomerUpstoxOAuthService;
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

/**
 * Customer-scoped custody for the isolated LIVE intraday equity ledger: the
 * serialized durable claim, its read side, and broker-history reconciliation.
 *
 * <p>This service never dispatches an order. A claim only reserves an intent
 * (persisting a unique correlation tag) after the authoritative preflight is
 * executable and all account-wide risk caps pass; reconciliation only reads the
 * owner's own selected-broker order book to prove a state. LIVE dispatch stays
 * disabled and is separately gated.
 */
@Service
public class CustomerLiveEquityOrderService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcClient jdbc;
    private final CustomerExecutionReadinessService readiness;
    private final CustomerAngelOneSessionService angelOne;
    private final CustomerUpstoxOAuthService upstox;

    public CustomerLiveEquityOrderService(JdbcClient jdbc,
            CustomerExecutionReadinessService readiness,
            CustomerAngelOneSessionService angelOne,
            CustomerUpstoxOAuthService upstox) {
        this.jdbc = jdbc;
        this.readiness = readiness;
        this.angelOne = angelOne;
        this.upstox = upstox;
    }

    public record Order(UUID id, UUID roomId, String broker, long instrumentId,
            String tradingSymbol, String exchange, String side, int quantity,
            BigDecimal limitPrice, String state, String brokerOrderId,
            int filledQuantity, String outcomeCode, Instant createdAt, Instant updatedAt) {}

    private record StoredOrder(Order order, String payloadHash) {}
    private record ClaimRoom(String mode, String marketScope, long plannedBudgetInr,
                             String selectedBroker) {}
    private record Reconcilable(String state, String broker, String brokerTag,
            String brokerOrderId, String exchange, String tradingSymbol, String side,
            int quantity, BigDecimal limitPrice, int filledQuantity) {}

    /**
     * Reserve a single LIVE cash-equity order after every preflight gate and
     * account-wide risk cap passes. The authoritative preflight is recomputed
     * here (never trusted from a caller snapshot), so market session, quote
     * freshness, tick size, broker permissions, registered IP, funds, room
     * budget, global halt and unreconciled-order checks all apply at claim time.
     * The customer row lock serializes reservations across every room for the
     * same owner. This method never contacts a broker to place an order; its
     * caller may dispatch once, only after this transaction commits.
     */
    @Transactional
    public Order claimForSubmission(UUID owner, UUID roomId, String idempotencyKey,
            long instrumentId, String side, int quantity, BigDecimal limitPrice) {
        if (owner == null || roomId == null || idempotencyKey == null
                || !idempotencyKey.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid LIVE intraday equity order claim");
        }
        // Recompute the authoritative preflight. This both validates the
        // instrument/intent and re-runs all mutable readiness checks at claim
        // time, so a stale client snapshot can never widen what is allowed.
        CustomerExecutionReadinessService.Preflight proposal =
            readiness.preflight(owner, roomId, instrumentId, side, quantity, limitPrice);
        if (proposal.instrumentId() != instrumentId
                || !"LIVE".equals(proposal.mode())
                || proposal.selectedBroker() == null
                || !("ANGEL_ONE".equals(proposal.selectedBroker())
                    || "UPSTOX".equals(proposal.selectedBroker()))
                || !("NSE".equals(proposal.exchange()) || "BSE".equals(proposal.exchange()))
                || !("BUY".equals(proposal.side()) || "SELL".equals(proposal.side()))
                || proposal.quantity() < 1 || proposal.quantity() > 10000
                || proposal.limitPrice() == null || proposal.limitPrice().signum() <= 0
                || proposal.limitPrice().scale() > 2
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
        // The reserved route fails closed while any blocker (always at least
        // EXECUTION_DISABLED in this release) is present.
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
        // Account-wide risk caps across ALL of the customer's LIVE rooms. A
        // missing/unreadable policy or exposure fails the claim closed.
        LiveEquityRiskPolicy policy = readRiskPolicy(owner);
        BigDecimal accountWideBuy = accountWideTodayBuyNotional(owner);
        List<String> riskBlockers = LiveEquityRiskPolicy.assessClaimRisk(
            proposal.side(), notional, proposal.quantity(), accountWideBuy,
            /* realized loss source not yet wired; fails closed */ null, policy);
        if (!riskBlockers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Account-wide LIVE intraday risk policy blocks this order");
        }

        UUID orderId = UUID.randomUUID();
        String brokerTag = LiveEquityCorrelationTag.generate();
        jdbc.sql("""
                INSERT INTO customer_live_equity_order
                    (id,room_id,user_id,mode,broker,instrument_id,trading_symbol,
                     exchange,side,quantity,limit_price,idempotency_key,payload_hash,
                     broker_tag,state,filled_quantity,outcome_code)
                VALUES (:id,:room,:owner,'LIVE',:broker,:instrument,:symbol,
                        :exchange,:side,:quantity,:price,:idempotency,:hash,
                        :tag,'SUBMITTING',0,'LIVE_EQUITY_ORDER_CLAIMED')
                """)
            .param("id", orderId).param("room", roomId).param("owner", owner)
            .param("broker", proposal.selectedBroker()).param("instrument", proposal.instrumentId())
            .param("symbol", proposal.tradingSymbol()).param("exchange", proposal.exchange())
            .param("side", proposal.side()).param("quantity", proposal.quantity())
            .param("price", proposal.limitPrice()).param("idempotency", idempotencyKey)
            .param("hash", fingerprint).param("tag", brokerTag).update();
        return byIdempotencyKey(owner, roomId, idempotencyKey).order();
    }

    @Transactional(readOnly = true)
    public List<Order> list(UUID owner, UUID roomId) {
        // First prove this is the caller's room; a foreign room returns 404 even if empty.
        readiness.readiness(owner, roomId);
        return jdbc.sql("""
            SELECT id,room_id,broker,instrument_id,trading_symbol,exchange,side,
                   quantity,limit_price,state,broker_order_id,filled_quantity,outcome_code,
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
                       quantity,limit_price,state,broker_order_id,filled_quantity,outcome_code,
                       created_at,updated_at,payload_hash
                FROM customer_live_equity_order
                WHERE user_id=:owner AND room_id=:room AND idempotency_key=:key
                """)
            .param("owner", owner).param("room", roomId).param("key", key)
            .query((rs,n) -> new StoredOrder(mapOrder(rs),rs.getString("payload_hash")))
            .optional().orElse(null);
    }

    private BigDecimal todayRoomBuyNotional(UUID owner, UUID roomId) {
        LocalDate today = LocalDate.now(IST);
        return jdbc.sql("""
                SELECT COALESCE(SUM(limit_price * quantity),0)
                FROM customer_live_equity_order
                WHERE user_id=:owner AND room_id=:room AND side='BUY'
                  AND state <> 'REJECTED'
                  AND created_at >= :start AND created_at < :end
                """)
            .param("owner", owner).param("room", roomId)
            .param("start", today.atStartOfDay(IST).toInstant())
            .param("end", today.plusDays(1).atStartOfDay(IST).toInstant())
            .query(BigDecimal.class).single();
    }

    /** Cumulative same-day BUY notional across ALL of the customer's LIVE rooms. */
    private BigDecimal accountWideTodayBuyNotional(UUID owner) {
        try {
            LocalDate today = LocalDate.now(IST);
            return jdbc.sql("""
                    SELECT COALESCE(SUM(limit_price * quantity),0)
                    FROM customer_live_equity_order
                    WHERE user_id=:owner AND side='BUY'
                      AND state <> 'REJECTED'
                      AND created_at >= :start AND created_at < :end
                    """)
                .param("owner", owner)
                .param("start", today.atStartOfDay(IST).toInstant())
                .param("end", today.plusDays(1).atStartOfDay(IST).toInstant())
                .query(BigDecimal.class).single();
        } catch (RuntimeException unavailable) {
            // Unreadable ledger is not a clean zero-exposure account.
            return null;
        }
    }

    private LiveEquityRiskPolicy readRiskPolicy(UUID owner) {
        try {
            return jdbc.sql("""
                    SELECT max_account_intraday_buy_inr,max_order_value_inr,
                           max_order_quantity,daily_realized_loss_limit_inr
                    FROM customer_live_equity_risk_policy WHERE user_id=:owner
                    """)
                .param("owner", owner)
                .query((rs,n) -> new LiveEquityRiskPolicy(
                    rs.getLong("max_account_intraday_buy_inr"),
                    rs.getLong("max_order_value_inr"),
                    rs.getInt("max_order_quantity"),
                    rs.getLong("daily_realized_loss_limit_inr")))
                .optional().orElse(null);
        } catch (RuntimeException unavailable) {
            // Unreadable policy must fail closed, never default to permissive.
            return null;
        }
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
            rs.getInt("filled_quantity"),rs.getString("outcome_code"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * Reconcile one reserved order using ONLY the authenticated owner's own
     * selected-broker session and an exact correlation-tag plus order-identity
     * match. Missing, stale, malformed, duplicated or contradictory broker
     * evidence leaves the order reserved and fails closed. This never retries or
     * submits an order; it only reads the broker's order book/history. Raw broker
     * errors and account data never cross this boundary.
     */
    @Transactional
    public Order reconcileFromBroker(UUID owner, UUID roomId, UUID orderId) {
        if (owner == null || roomId == null || orderId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reconcile request");
        }
        readiness.readiness(owner, roomId); // prove ownership; foreign room is 404
        Reconcilable o = jdbc.sql("""
                SELECT state,broker,broker_tag,broker_order_id,exchange,trading_symbol,
                       side,quantity,limit_price,filled_quantity
                FROM customer_live_equity_order
                WHERE id=:id AND user_id=:owner AND room_id=:room
                FOR UPDATE
                """)
            .param("id", orderId).param("owner", owner).param("room", roomId)
            .query((rs,n) -> new Reconcilable(rs.getString("state"),rs.getString("broker"),
                rs.getString("broker_tag"),rs.getString("broker_order_id"),
                rs.getString("exchange"),rs.getString("trading_symbol"),rs.getString("side"),
                rs.getInt("quantity"),rs.getBigDecimal("limit_price"),rs.getInt("filled_quantity")))
            .optional().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "LIVE equity order not found"));
        LiveEquityOrderState current;
        try {
            current = LiveEquityOrderState.valueOf(o.state());
        } catch (IllegalArgumentException invalidStoredState) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Stored order state requires operator review");
        }
        if (!current.requiresReconciliation()) {
            return requireOrder(owner, roomId, orderId); // terminal: nothing to do
        }

        List<BrokerOrderRecord> records = readBrokerRecords(o.broker(), owner, o.brokerTag());
        var expected = new LiveEquityReconciler.Expected(o.broker(), o.brokerTag(),
            o.exchange(), o.tradingSymbol(), o.side(), o.quantity(), o.limitPrice(),
            o.brokerOrderId());
        LiveEquityReconciler.Resolution res = LiveEquityReconciler.reconcile(expected, records);

        if (res.reserved()) {
            // Record only the sanitized reconcile outcome; never change state.
            jdbc.sql("""
                    UPDATE customer_live_equity_order
                    SET outcome_code=:code, updated_at=NOW()
                    WHERE id=:id AND user_id=:owner AND room_id=:room AND state=:state
                    """)
                .param("code", "RECONCILE_RESERVED:" + res.outcomeCode())
                .param("id", orderId).param("owner", owner).param("room", roomId)
                .param("state", current.name()).update();
            return requireOrder(owner, roomId, orderId);
        }

        LiveEquityOrderState observed = res.observedState();
        // Fills must never regress; a lower broker-reported fill is contradictory.
        if (res.filledQuantity() < o.filledQuantity()) {
            jdbc.sql("""
                    UPDATE customer_live_equity_order
                    SET outcome_code='RECONCILE_RESERVED:FILL_QUANTITY_REGRESSED', updated_at=NOW()
                    WHERE id=:id AND user_id=:owner AND room_id=:room AND state=:state
                    """)
                .param("id", orderId).param("owner", owner).param("room", roomId)
                .param("state", current.name()).update();
            return requireOrder(owner, roomId, orderId);
        }
        // Identity: once a broker order id is known it must not change.
        if (o.brokerOrderId() != null && res.brokerOrderId() != null
                && !o.brokerOrderId().equals(res.brokerOrderId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Broker order identity changed during reconciliation");
        }
        boolean sameState = observed == current;
        if (!sameState && !current.mayTransitionTo(observed)) {
            // e.g. broker now reports a state the machine forbids from here.
            jdbc.sql("""
                    UPDATE customer_live_equity_order
                    SET outcome_code='RECONCILE_RESERVED:DISALLOWED_TRANSITION', updated_at=NOW()
                    WHERE id=:id AND user_id=:owner AND room_id=:room AND state=:state
                    """)
                .param("id", orderId).param("owner", owner).param("room", roomId)
                .param("state", current.name()).update();
            return requireOrder(owner, roomId, orderId);
        }
        if (sameState && current != LiveEquityOrderState.ACKNOWLEDGED
                && current != LiveEquityOrderState.PARTIALLY_FILLED) {
            // Only non-terminal observe-in-place states may re-record fills.
            return requireOrder(owner, roomId, orderId);
        }
        int updated = jdbc.sql("""
                UPDATE customer_live_equity_order
                SET state=:observed,
                    broker_order_id=COALESCE(broker_order_id,:brokerOrderId),
                    filled_quantity=:filled,
                    average_fill_price=:avg,
                    outcome_code=:code, updated_at=NOW()
                WHERE id=:id AND user_id=:owner AND room_id=:room AND state=:expected
                """)
            .param("observed", observed.name())
            .param("brokerOrderId", res.brokerOrderId())
            .param("filled", res.filledQuantity())
            .param("avg", res.averageFillPrice())
            .param("code", res.outcomeCode())
            .param("id", orderId).param("owner", owner).param("room", roomId)
            .param("expected", current.name()).update();
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Order changed during reconciliation");
        }
        return requireOrder(owner, roomId, orderId);
    }

    /**
     * Read the owner's own broker order book/history for this correlation tag.
     * Any provider failure or absent session yields {@code null} so the
     * reconciler fails closed. Broker payloads and errors are not logged.
     */
    private List<BrokerOrderRecord> readBrokerRecords(String broker, UUID owner, String tag) {
        try {
            if ("UPSTOX".equals(broker)) {
                JsonNode data = upstox.orderHistoryByTag(owner, tag).data();
                return UpstoxEquityOrderHistoryParser.parse(data);
            }
            if ("ANGEL_ONE".equals(broker)) {
                JsonNode body = angelOne.orders(owner).data();
                JsonNode array = body == null ? null : body.path("data");
                return AngelOneEquityOrderBookParser.parse(array);
            }
        } catch (RuntimeException providerUnavailable) {
            return null;
        }
        return null;
    }

    private Order requireOrder(UUID owner, UUID roomId, UUID orderId) {
        return jdbc.sql("""
                SELECT id,room_id,broker,instrument_id,trading_symbol,exchange,side,
                       quantity,limit_price,state,broker_order_id,filled_quantity,outcome_code,
                       created_at,updated_at
                FROM customer_live_equity_order
                WHERE id=:id AND user_id=:owner AND room_id=:room
                """)
            .param("id", orderId).param("owner", owner).param("room", roomId)
            .query((rs,n) -> mapOrder(rs)).single();
    }
}
