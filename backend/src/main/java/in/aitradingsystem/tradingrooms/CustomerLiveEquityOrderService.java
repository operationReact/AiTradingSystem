package in.aitradingsystem.tradingrooms;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Durable custody for the LIVE intraday equity ledger: the serialized claim,
 * the single outcome write after a dispatch, and broker-history reconciliation.
 *
 * <p>This service never contacts a broker to place an order. The broker
 * request itself lives in {@code LiveEquityOrderCoordinator}, which runs it
 * strictly after this class's claim transaction has committed.
 */
@Service
public class CustomerLiveEquityOrderService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcClient jdbc;
    private final CustomerExecutionReadinessService readiness;
    private final LiveEquityRiskLimits configuredRiskLimits;

    public CustomerLiveEquityOrderService(JdbcClient jdbc,
            CustomerExecutionReadinessService readiness) {
        this(jdbc, readiness, LiveEquityRiskLimits.disabled());
    }

    @Autowired
    public CustomerLiveEquityOrderService(JdbcClient jdbc,
            CustomerExecutionReadinessService readiness, LiveEquityRiskLimits configuredRiskLimits) {
        this.jdbc = jdbc;
        this.readiness = readiness;
        this.configuredRiskLimits = configuredRiskLimits == null
            ? LiveEquityRiskLimits.disabled() : configuredRiskLimits;
    }

    public record Order(UUID id, UUID roomId, String broker, long instrumentId,
            String tradingSymbol, String exchange, String side, int quantity,
            BigDecimal limitPrice, String state, String brokerOrderId,
            int filledQuantity, String outcomeCode, Instant createdAt, Instant updatedAt) {}

    /**
     * {@code created=false} means an idempotent replay: never dispatch again and
     * {@code brokerTag} is null. {@code brokerTag} is internal and never serialized.
     */
    public record Claim(Order order, boolean created, String brokerTag) {
        @Override public String toString() { return "Claim[redacted]"; }
    }

    private record StoredOrder(Order order, String payloadHash) {}

    /** A broker-backed preflight older than this is never used for a new claim. */
    static final Duration PROPOSAL_MAX_AGE = Duration.ofSeconds(10);
    private record ClaimRoom(String mode, String marketScope, long plannedBudgetInr,
                             String selectedBroker) {}
    /** Order snapshot read before any broker I/O, re-checked under lock before applying. */
    public record Reconcilable(String state, String broker, String brokerTag,
            String brokerOrderId, String exchange, String tradingSymbol, String side,
            int quantity, BigDecimal limitPrice, int filledQuantity) {}

    /**
     * Reserve a single LIVE cash-equity order. The coordinator supplies a fresh,
     * time-limited broker-backed preflight before this transaction. This transaction
     * validates the proposal and recomputes database-owned room, halt, exposure, and
     * account-wide risk gates under the owner's row lock. Broker network calls stay
     * outside the transaction. Per-order consent is required. The durable SUBMITTING
     * row and unique correlation tag are committed before any broker request.
     */
    @Transactional
    public Claim claimForSubmission(UUID owner, UUID roomId, String idempotencyKey,
            CustomerExecutionReadinessService.Preflight proposal, boolean consentConfirmed) {
        if (owner == null || roomId == null || idempotencyKey == null
                || !idempotencyKey.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid LIVE intraday equity order claim");
        }
        if (!consentConfirmed) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                "Explicit per-order LIVE equity confirmation required");
        }
        // Broker-backed checks (session, quote, funds, positions, hosting) ran
        // BEFORE this transaction, in the coordinator. This transaction owns only
        // the database-authoritative checks below, under the owner's row lock. A
        // proposal older than PROPOSAL_MAX_AGE is refused so broker-side facts
        // cannot drift far from the claim.
        if (proposal == null || proposal.evaluatedAt() == null
                || Duration.between(proposal.evaluatedAt(), Instant.now()).compareTo(PROPOSAL_MAX_AGE) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "LIVE intraday equity preflight expired; retry the order");
        }
        if (proposal.roomId() == null || !roomId.equals(proposal.roomId())
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
            return new Claim(prior.order(), false, null);
        }
        // Any blocker (including EXECUTION_DISABLED while the dispatch gate is off)
        // refuses the claim. Nothing is reserved and nothing is sent.
        if (!proposal.blockers().isEmpty()) {
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
        if (!"LIVE".equals(room.mode()) || !"EQUITY".equals(room.marketScope())
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
        // Account-wide caps across ALL of the customer's LIVE rooms. Missing or
        // unreadable policy, exposure or day loss fails the claim closed.
        LiveEquityRiskPolicy policy = readRiskPolicy(owner);
        BigDecimal accountWideBuy = accountWideTodayBuyNotional(owner);
        List<String> riskBlockers = LiveEquityRiskPolicy.assessClaimRisk(
            proposal.side(), notional, proposal.quantity(), accountWideBuy,
            proposal.todayRealisedLoss(), policy);
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
        return new Claim(byIdempotencyKey(owner, roomId, idempotencyKey).order(), true, brokerTag);
    }

    /**
     * Record the single dispatch outcome. The write succeeds only from
     * SUBMITTING, so a stale or duplicate writer cannot overwrite a recorded
     * state. An ACKNOWLEDGED outcome without a broker order id is downgraded to
     * UNKNOWN, which stays reserved until reconciliation.
     */
    @Transactional
    public Order recordDispatchOutcome(UUID owner, UUID roomId, UUID orderId,
            LiveEquityOrderState observed, String brokerOrderId, String outcomeCode) {
        if (observed == null || outcomeCode == null
                || !outcomeCode.matches("[A-Z0-9_:-]{1,64}")
                || !(observed == LiveEquityOrderState.ACKNOWLEDGED
                    || observed == LiveEquityOrderState.REJECTED
                    || observed == LiveEquityOrderState.UNKNOWN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid dispatch outcome");
        }
        String state = jdbc.sql("""
                SELECT state FROM customer_live_equity_order
                WHERE id=:id AND user_id=:owner AND room_id=:room FOR UPDATE
                """)
            .param("id", orderId).param("owner", owner).param("room", roomId)
            .query(String.class).optional().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "LIVE equity order not found"));
        if (!"SUBMITTING".equals(state)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Dispatch outcome already recorded");
        }
        LiveEquityOrderState effective = observed;
        String effectiveCode = outcomeCode;
        String effectiveBrokerId = null;
        if (observed == LiveEquityOrderState.ACKNOWLEDGED) {
            if (brokerOrderId != null && brokerOrderId.matches("[A-Za-z0-9_-]{1,64}")) {
                effectiveBrokerId = brokerOrderId;
            } else {
                effective = LiveEquityOrderState.UNKNOWN;
                effectiveCode = "BROKER_ACK_WITHOUT_ORDER_ID";
            }
        }
        jdbc.sql("""
                UPDATE customer_live_equity_order
                SET state=:state, broker_order_id=:brokerOrderId,
                    outcome_code=:code, updated_at=NOW()
                WHERE id=:id AND user_id=:owner AND room_id=:room AND state='SUBMITTING'
                """)
            .param("state", effective.name())
            .param("brokerOrderId", effectiveBrokerId)
            .param("code", effectiveCode)
            .param("id", orderId).param("owner", owner).param("room", roomId)
            .update();
        return requireOrder(owner, roomId, orderId);
    }

    /**
     * Idempotent replay without any broker call. Returns the stored order only when
     * the request matches it exactly; a different order under the same key is a
     * conflict, never a second dispatch.
     */
    @Transactional(readOnly = true)
    public Optional<Claim> findReplay(UUID owner, UUID roomId, String idempotencyKey,
            long instrumentId, String side, int quantity, BigDecimal limitPrice) {
        StoredOrder prior = byIdempotencyKey(owner, roomId, idempotencyKey);
        if (prior == null) {
            return Optional.empty();
        }
        Order o = prior.order();
        if (o.instrumentId() != instrumentId || !Objects.equals(o.side(), side)
                || o.quantity() != quantity || o.limitPrice().compareTo(limitPrice) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Idempotency key was already used for a different order");
        }
        return Optional.of(new Claim(o, false, null));
    }

    @Transactional(readOnly = true)
    public List<Order> list(UUID owner, UUID roomId) {
        // First prove this is the caller's room; a foreign room returns 404 even if empty.
        readiness.requireOwnedRoom(owner, roomId);
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
            .param("start", java.sql.Timestamp.from(today.atStartOfDay(IST).toInstant()))
            .param("end", java.sql.Timestamp.from(today.plusDays(1).atStartOfDay(IST).toInstant()))
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
                .param("start", java.sql.Timestamp.from(today.atStartOfDay(IST).toInstant()))
                .param("end", java.sql.Timestamp.from(today.plusDays(1).atStartOfDay(IST).toInstant()))
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
                .optional().orElseGet(() -> configuredRiskLimits.configuredPolicy().orElse(null));
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

    /** Read-only snapshot used before any broker call. Holds no row lock. */
    @Transactional(readOnly = true)
    public Reconcilable loadForReconcile(UUID owner, UUID roomId, UUID orderId) {
        return jdbc.sql("""
                SELECT state,broker,broker_tag,broker_order_id,exchange,trading_symbol,
                       side,quantity,limit_price,filled_quantity
                FROM customer_live_equity_order
                WHERE id=:id AND user_id=:owner AND room_id=:room
                """)
            .param("id", orderId).param("owner", owner).param("room", roomId)
            .query((rs,n) -> new Reconcilable(rs.getString("state"),rs.getString("broker"),
                rs.getString("broker_tag"),rs.getString("broker_order_id"),
                rs.getString("exchange"),rs.getString("trading_symbol"),rs.getString("side"),
                rs.getInt("quantity"),rs.getBigDecimal("limit_price"),rs.getInt("filled_quantity")))
            .optional().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "LIVE equity order not found"));
    }

    @Transactional(readOnly = true)
    public Order getOrder(UUID owner, UUID roomId, UUID orderId) {
        return requireOrder(owner, roomId, orderId);
    }

    /**
     * Apply a reconciliation decision computed from broker evidence read OUTSIDE
     * this transaction. The row is locked and re-checked against the snapshot the
     * decision was based on; any change since then is a conflict, never a blind write.
     * No broker call happens here.
     */
    @Transactional
    public Order applyReconciliation(UUID owner, UUID roomId, UUID orderId,
            Reconcilable seen, LiveEquityReconciler.Resolution res) {
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
        if (!o.state().equals(seen.state()) || o.filledQuantity() != seen.filledQuantity()
                || !Objects.equals(o.brokerOrderId(), seen.brokerOrderId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Order changed during reconciliation; retry the reconciliation");
        }
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
        if (res.reserved()) {
            recordReconcileReserved(owner, roomId, orderId, current, res.outcomeCode());
            return requireOrder(owner, roomId, orderId);
        }
        LiveEquityOrderState observed = res.observedState();
        // Fills must never regress; a lower broker-reported fill is contradictory.
        if (res.filledQuantity() < o.filledQuantity()) {
            recordReconcileReserved(owner, roomId, orderId, current, "FILL_QUANTITY_REGRESSED");
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
            recordReconcileReserved(owner, roomId, orderId, current, "DISALLOWED_TRANSITION");
            return requireOrder(owner, roomId, orderId);
        }
        if (sameState && current != LiveEquityOrderState.ACKNOWLEDGED
                && current != LiveEquityOrderState.PARTIALLY_FILLED
                && current != LiveEquityOrderState.UNKNOWN) {
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

    private void recordReconcileReserved(UUID owner, UUID roomId, UUID orderId,
            LiveEquityOrderState current, String reason) {
        jdbc.sql("""
                UPDATE customer_live_equity_order
                SET outcome_code=:code, updated_at=NOW()
                WHERE id=:id AND user_id=:owner AND room_id=:room AND state=:state
                """)
            .param("code", "RECONCILE_RESERVED:" + reason)
            .param("id", orderId).param("owner", owner).param("room", roomId)
            .param("state", current.name()).update();
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
