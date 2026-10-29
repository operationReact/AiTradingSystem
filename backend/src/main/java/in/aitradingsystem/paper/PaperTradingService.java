package in.aitradingsystem.paper;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.common.CorrelationIdFilter;
import in.aitradingsystem.common.IdempotencyConflictException;
import in.aitradingsystem.common.NotFoundException;
import in.aitradingsystem.domain.OrderStatus;
import in.aitradingsystem.domain.Side;
import in.aitradingsystem.paper.PaperViews.DecisionView;
import in.aitradingsystem.paper.PaperViews.FillView;
import in.aitradingsystem.paper.PaperViews.OrderResult;
import in.aitradingsystem.paper.PaperViews.OrderView;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskContextFactory;
import in.aitradingsystem.risk.RiskDecisionClass;
import in.aitradingsystem.risk.RiskDecisionPersistenceService;
import in.aitradingsystem.risk.RiskDecisionV2;
import in.aitradingsystem.risk.RiskOutcome;
import in.aitradingsystem.risk.RiskPolicyEngine;
import in.aitradingsystem.risk.RiskRuleResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paper-only order execution. Fresh orders serialize on the paper-account row before any risk state is built or
 * mutated. Idempotent replays return the originally persisted decision without re-evaluating current risk state.
 */
@Service
public class PaperTradingService {
    static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private final JdbcClient jdbc;
    private final RiskContextFactory contextFactory;
    private final RiskPolicyEngine riskEngine;
    private final RiskDecisionPersistenceService decisions;
    private final AuditService audit;
    private final Clock clock;

    public PaperTradingService(JdbcClient jdbc, RiskContextFactory contextFactory, RiskPolicyEngine riskEngine,
                               RiskDecisionPersistenceService decisions, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.contextFactory = contextFactory;
        this.riskEngine = riskEngine;
        this.decisions = decisions;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public OrderResult placeOrder(OrderCommand cmd, String idempotencyKey) {
        String requestHash = cmd.canonicalHash();

        Optional<ExistingOrder> existing = findExisting(idempotencyKey);
        if (existing.isPresent()) return replay(existing.get(), requestHash, idempotencyKey);

        AccountSnapshot account = lockAccount();

        existing = findExisting(idempotencyKey);
        if (existing.isPresent()) return replay(existing.get(), requestHash, idempotencyKey);

        UUID orderId = UUID.randomUUID();
        Instant now = clock.instant();
        insertOrder(orderId, idempotencyKey, requestHash, cmd, now);

        Optional<PositionRow> held = findPosition(cmd.symbol());
        RiskContext context = contextFactory.build(orderId, cmd, now);
        enrichOrderIdentity(orderId, context, now);
        RiskDecisionV2 decision = riskEngine.evaluate(context);
        decisions.persist(orderId, context, decision);

        if (decision.decisionClass() != RiskDecisionClass.APPROVED) {
            String reasons = reasonsText(decision.ruleResults());
            transition(orderId, OrderStatus.NEW, OrderStatus.REJECTED, reasons, now);
            audit.record("ORDER_REJECTED", "PAPER_ORDER", orderId.toString(),
                    Map.of("symbol", cmd.symbol(), "side", cmd.side().name(),
                            "decisionClass", decision.decisionClass().name(), "reasons", reasons));
            return loadResult(orderId, false);
        }

        transition(orderId, OrderStatus.NEW, OrderStatus.RISK_APPROVED, null, now);
        applyFill(orderId, cmd, decision, account.cash(), held.orElse(null), now);
        transition(orderId, OrderStatus.RISK_APPROVED, OrderStatus.FILLED, null, now);
        audit.record("ORDER_FILLED", "PAPER_ORDER", orderId.toString(),
                Map.of("symbol", cmd.symbol(), "side", cmd.side().name(), "quantity", cmd.quantity(),
                        "fillPrice", decision.effectiveFillPrice(), "policyVersion", decision.policyVersion()));
        return loadResult(orderId, false);
    }

    @Transactional
    public void setNewOrdersHalted(boolean halted, String reason) {
        jdbc.sql("UPDATE trading_control SET new_orders_halted = :halted, reason = :reason, updated_at = :now WHERE id = 1")
            .param("halted", halted)
            .param("reason", reason, Types.VARCHAR)
            .param("now", ts(clock.instant()))
            .update();
        audit.record("EMERGENCY_STOP_CHANGED", "TRADING_CONTROL", "1",
            Map.of("newOrdersHalted", halted, "reason", reason == null ? "" : reason));
    }

    public boolean isHalted() {
        return jdbc.sql("SELECT new_orders_halted FROM trading_control WHERE id = 1")
            .query(Boolean.class).single();
    }

    private Optional<ExistingOrder> findExisting(String idempotencyKey) {
        return jdbc.sql("SELECT id, request_hash FROM paper_order WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query((rs, n) -> new ExistingOrder(rs.getObject("id", UUID.class), rs.getString("request_hash")))
                .optional();
    }

    private OrderResult replay(ExistingOrder existing, String requestHash, String idempotencyKey) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
        return loadResult(existing.id(), true);
    }

    private AccountSnapshot lockAccount() {
        return jdbc.sql("SELECT starting_capital, cash_balance FROM paper_account WHERE id = 1 FOR UPDATE")
            .query((rs, n) -> new AccountSnapshot(rs.getBigDecimal("starting_capital"), rs.getBigDecimal("cash_balance")))
            .optional()
            .orElseThrow(() -> new IllegalStateException("Paper account is not initialized"));
    }

    private void insertOrder(UUID id, String key, String hash, OrderCommand cmd, Instant now) {
        jdbc.sql("""
                INSERT INTO paper_order (id, idempotency_key, request_hash, symbol, side, quantity, submitted_quantity,
                    filled_quantity, order_type, product_type, reference_price, stop_loss, target_price,
                    signal_generated_at, status, correlation_id, created_at, updated_at)
                VALUES (:id, :key, :hash, :symbol, :side, :qty, :qty, 0, 'MARKET', 'DELIVERY',
                    :ref, :stop, :target, :signal, 'NEW', :cid, :now, :now)
                """)
            .param("id", id)
            .param("key", key)
            .param("hash", hash)
            .param("symbol", cmd.symbol())
            .param("side", cmd.side().name())
            .param("qty", cmd.quantity())
            .param("ref", cmd.entryPrice())
            .param("stop", cmd.stopLoss(), Types.NUMERIC)
            .param("target", cmd.target(), Types.NUMERIC)
            .param("signal", cmd.signalGeneratedAt() == null ? null : ts(cmd.signalGeneratedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
            .param("cid", MDC.get(CorrelationIdFilter.MDC_KEY), Types.VARCHAR)
            .param("now", ts(now))
            .update();
    }

    /**
     * P06-W01 stores authoritative identity resolved by the server-side risk context. Synthetic compatibility
     * identities are intentionally not persisted as instrument FKs.
     */
    private void enrichOrderIdentity(UUID orderId, RiskContext context, Instant now) {
        Long instrumentId = "PAPER".equalsIgnoreCase(context.exchange()) ? null : context.instrumentId();
        jdbc.sql("""
                UPDATE paper_order
                   SET instrument_id = :instrumentId,
                       exchange = :exchange,
                       strategy = :strategy,
                       product_type = :productType,
                       updated_at = :now
                 WHERE id = :id
                """)
            .param("instrumentId", instrumentId, Types.BIGINT)
            .param("exchange", context.exchange(), Types.VARCHAR)
            .param("strategy", context.strategy(), Types.VARCHAR)
            .param("productType", context.tradingStyle().name(), Types.VARCHAR)
            .param("now", ts(now))
            .param("id", orderId)
            .update();
    }

    private void transition(UUID orderId, OrderStatus from, OrderStatus to, String reason, Instant now) {
        from.transitionTo(to);
        int updated = jdbc.sql("UPDATE paper_order SET status = :to, rejection_reason = :reason, updated_at = :now "
                + "WHERE id = :id AND status = :from")
            .param("to", to.name())
            .param("reason", reason, Types.VARCHAR)
            .param("now", ts(now))
            .param("id", orderId)
            .param("from", from.name())
            .update();
        if (updated != 1) {
            throw new IllegalStateException("Order " + orderId + " was not in state " + from);
        }
    }

    private void applyFill(UUID orderId, OrderCommand cmd, RiskDecisionV2 d, BigDecimal cashBefore,
                           PositionRow held, Instant now) {
        BigDecimal cashAfter;
        BigDecimal costBasis;
        BigDecimal realized = BigDecimal.ZERO.setScale(2);
        BigDecimal netAmount = netAmount(cmd.side(), d);

        if (cmd.side() == Side.BUY) {
            costBasis = d.grossNotional().add(d.estimatedFee());
            cashAfter = cashBefore.subtract(costBasis);
            insertPosition(cmd, costBasis, now);
        } else {
            BigDecimal qty = BigDecimal.valueOf(cmd.quantity());
            costBasis = held.quantity() == cmd.quantity()
                ? held.costBasis()
                : held.costBasis().multiply(qty).divide(BigDecimal.valueOf(held.quantity()), 2, RoundingMode.HALF_UP);
            realized = d.grossNotional().subtract(d.estimatedFee()).subtract(costBasis).setScale(2, RoundingMode.HALF_UP);
            cashAfter = cashBefore.add(netAmount);
            reducePosition(held, cmd.quantity(), costBasis, now);
        }

        jdbc.sql("UPDATE paper_account SET cash_balance = :cash, updated_at = :now WHERE id = 1")
            .param("cash", cashAfter)
            .param("now", ts(now))
            .update();
        jdbc.sql("INSERT INTO paper_cash_entry (order_id, entry_type, amount, balance_after, created_at) "
                + "VALUES (:orderId, :type, :amount, :after, :now)")
            .param("orderId", orderId)
            .param("type", cmd.side() == Side.BUY ? "BUY_SETTLEMENT" : "SELL_SETTLEMENT")
            .param("amount", netAmount)
            .param("after", cashAfter)
            .param("now", ts(now))
            .update();
        jdbc.sql("""
                INSERT INTO paper_fill (id, order_id, symbol, side, quantity, reference_price, fill_price, gross_amount,
                    fee_amount, slippage_amount, net_amount, cost_basis, realized_pnl, filled_at)
                VALUES (:id, :orderId, :symbol, :side, :qty, :ref, :fill, :gross, :fee, :slip, :net, :basis, :pnl, :now)
                """)
            .param("id", UUID.randomUUID())
            .param("orderId", orderId)
            .param("symbol", cmd.symbol())
            .param("side", cmd.side().name())
            .param("qty", cmd.quantity())
            .param("ref", cmd.entryPrice())
            .param("fill", d.effectiveFillPrice())
            .param("gross", d.grossNotional())
            .param("fee", d.estimatedFee())
            .param("slip", d.estimatedSlippage())
            .param("net", netAmount)
            .param("basis", costBasis)
            .param("pnl", realized)
            .param("now", ts(now))
            .update();
        jdbc.sql("""
                UPDATE paper_order
                   SET filled_quantity = :qty,
                       average_fill_price = :fill,
                       updated_at = :now
                 WHERE id = :id
                """)
            .param("qty", cmd.quantity())
            .param("fill", d.effectiveFillPrice())
            .param("now", ts(now))
            .param("id", orderId)
            .update();
    }

    private static BigDecimal netAmount(Side side, RiskDecisionV2 decision) {
        return side == Side.BUY
                ? decision.grossNotional().add(decision.estimatedFee()).negate()
                : decision.grossNotional().subtract(decision.estimatedFee());
    }

    private void insertPosition(OrderCommand cmd, BigDecimal costBasis, Instant now) {
        jdbc.sql("INSERT INTO paper_position (symbol, quantity, cost_basis, stop_loss, target_price, opened_at, updated_at) "
                + "VALUES (:symbol, :qty, :basis, :stop, :target, :now, :now)")
            .param("symbol", cmd.symbol())
            .param("qty", cmd.quantity())
            .param("basis", costBasis)
            .param("stop", cmd.stopLoss())
            .param("target", cmd.target())
            .param("now", ts(now))
            .update();
    }

    private void reducePosition(PositionRow held, int soldQuantity, BigDecimal soldCostBasis, Instant now) {
        if (held.quantity() == soldQuantity) {
            jdbc.sql("DELETE FROM paper_position WHERE symbol = :symbol").param("symbol", held.symbol()).update();
            return;
        }
        jdbc.sql("UPDATE paper_position SET quantity = :qty, cost_basis = :basis, updated_at = :now WHERE symbol = :symbol")
            .param("qty", held.quantity() - soldQuantity)
            .param("basis", held.costBasis().subtract(soldCostBasis))
            .param("now", ts(now))
            .param("symbol", held.symbol())
            .update();
    }

    private Optional<PositionRow> findPosition(String symbol) {
        return jdbc.sql("SELECT symbol, quantity, cost_basis FROM paper_position WHERE symbol = :symbol")
            .param("symbol", symbol)
            .query((rs, n) -> new PositionRow(rs.getString("symbol"), rs.getInt("quantity"), rs.getBigDecimal("cost_basis")))
            .optional();
    }

    Instant startOfMarketDay(Instant now) {
        return now.atZone(MARKET_ZONE).toLocalDate().atStartOfDay(MARKET_ZONE).toInstant();
    }

    OrderResult loadResult(UUID orderId, boolean replayed) {
        OrderView order = jdbc.sql("SELECT * FROM paper_order WHERE id = :id").param("id", orderId)
            .query((rs, n) -> toOrderView(rs)).optional()
            .orElseThrow(() -> new NotFoundException("Paper order not found: " + orderId));
        DecisionView decision = jdbc.sql("SELECT * FROM risk_decision WHERE order_id = :id").param("id", orderId)
            .query((rs, n) -> toDecisionView(rs)).optional().orElse(null);
        FillView fill = jdbc.sql("SELECT * FROM paper_fill WHERE order_id = :id").param("id", orderId)
            .query((rs, n) -> new FillView(rs.getObject("id", UUID.class), rs.getBigDecimal("fill_price"),
                rs.getBigDecimal("gross_amount"), rs.getBigDecimal("fee_amount"), rs.getBigDecimal("slippage_amount"),
                rs.getBigDecimal("net_amount"), rs.getBigDecimal("cost_basis"), rs.getBigDecimal("realized_pnl"),
                instant(rs, "filled_at")))
            .optional().orElse(null);
        return new OrderResult(order, decision, fill, replayed);
    }

    static OrderView toOrderView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderView(
            rs.getObject("id", UUID.class),
            rs.getObject("instrument_id", Long.class),
            rs.getString("exchange"),
            rs.getString("symbol"),
            rs.getString("side"),
            rs.getInt("quantity"),
            rs.getInt("submitted_quantity"),
            rs.getInt("filled_quantity"),
            rs.getString("order_type"),
            rs.getBigDecimal("limit_price"),
            rs.getString("product_type"),
            rs.getString("strategy"),
            rs.getObject("candidate_id", Long.class),
            rs.getBigDecimal("average_fill_price"),
            rs.getBigDecimal("reference_price"),
            rs.getBigDecimal("stop_loss"),
            rs.getBigDecimal("target_price"),
            rs.getString("status"),
            rs.getString("rejection_reason"),
            instant(rs, "signal_generated_at"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            rs.getString("correlation_id"));
    }

    static DecisionView toDecisionView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DecisionView(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
            rs.getString("decision"), parseReasons(rs.getString("reason_codes")),
            rs.getBigDecimal("risk_budget"), rs.getBigDecimal("estimated_risk"),
            rs.getBigDecimal("available_cash"), rs.getInt("open_positions"), rs.getBigDecimal("realized_pnl_today"),
            rs.getBigDecimal("daily_loss_limit"), rs.getString("policy_version"), instant(rs, "evaluated_at"));
    }

    static List<String> parseReasons(String json) {
        String inner = json.replace("[", "").replace("]", "").replace("\"", "").trim();
        return inner.isEmpty() ? List.of() : Arrays.stream(inner.split(",")).map(String::trim).toList();
    }

    static String reasonsText(List<RiskRuleResult> results) {
        return results.stream()
                .filter(result -> result.outcome() != RiskOutcome.PASS)
                .map(RiskRuleResult::reasonCode)
                .collect(Collectors.joining(","));
    }

    static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record AccountSnapshot(BigDecimal startingCapital, BigDecimal cash) {}
    private record ExistingOrder(UUID id, String requestHash) {}
    private record PositionRow(String symbol, int quantity, BigDecimal costBasis) {}
}
