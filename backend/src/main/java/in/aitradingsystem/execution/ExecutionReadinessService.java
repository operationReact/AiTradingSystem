package in.aitradingsystem.execution;

import in.aitradingsystem.domain.ProductType;
import in.aitradingsystem.domain.Side;
import in.aitradingsystem.marketdata.IndianCashMarketCalendar;
import in.aitradingsystem.paper.OrderCommand;
import in.aitradingsystem.risk.ExecutionMode;
import in.aitradingsystem.risk.MarketSessionState;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskContextFactory;
import in.aitradingsystem.risk.RiskDecisionClass;
import in.aitradingsystem.risk.RiskDecisionV2;
import in.aitradingsystem.risk.RiskPolicyEngine;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Rebuilds execution truth immediately before fill/submission. Approval is evidence for one request, not a durable
 * bypass around fresh market/account/risk/session state.
 */
@Service
public class ExecutionReadinessService {
    private final JdbcClient jdbc;
    private final RiskContextFactory contextFactory;
    private final RiskPolicyEngine riskEngine;
    private final ExecutionPolicyService policies;
    private final ApprovalPolicyEvaluator policyEvaluator;
    private final Clock clock;

    public ExecutionReadinessService(JdbcClient jdbc, RiskContextFactory contextFactory, RiskPolicyEngine riskEngine,
                                     ExecutionPolicyService policies, ApprovalPolicyEvaluator policyEvaluator,
                                     Clock clock) {
        this.jdbc = jdbc;
        this.contextFactory = contextFactory;
        this.riskEngine = riskEngine;
        this.policies = policies;
        this.policyEvaluator = policyEvaluator;
        this.clock = clock;
    }

    public ExecutionReadinessResult revalidate(UUID orderId) {
        if (orderId == null) throw new IllegalArgumentException("orderId is required");
        Instant now = clock.instant();
        PersistedOrder order = loadOrder(orderId);
        Approval approval = loadApproval(order);

        if (!approval.expiresAt().isAfter(now)) {
            throw notReady("APPROVAL_EXPIRED", "Approval expired before execution");
        }
        if (approval.state() != ApprovalState.APPROVED && approval.state() != ApprovalState.AUTO_APPROVED) {
            throw notReady("APPROVAL_NOT_GRANTED", "Approval state is " + approval.state());
        }

        ExecutionPolicyTradingStyle style = order.productType() == ProductType.INTRADAY
            ? ExecutionPolicyTradingStyle.INTRADAY : ExecutionPolicyTradingStyle.SWING;
        ExecutionPolicy policy;
        try {
            policy = policies.requirePolicy(ExecutionMode.PAPER, order.strategy(), style);
        } catch (IllegalStateException ex) {
            throw notReady("EXECUTION_POLICY_UNAVAILABLE", ex.getMessage());
        }
        if (!policy.id().equals(approval.policyId()) || policy.policyVersion() != approval.policyVersion()) {
            throw notReady("EXECUTION_POLICY_CHANGED", "Approved policy is no longer the active resolved policy");
        }

        // Serialize emergency-stop changes with the settlement transaction after the account lock is acquired.
        jdbc.sql("SELECT new_orders_halted FROM trading_control WHERE id = 1 FOR SHARE").query(Boolean.class).single();
        if (jdbc.sql("SELECT id FROM risk_policy_config WHERE active = TRUE FOR SHARE").query(UUID.class).list().isEmpty()) {
            throw notReady("RISK_POLICY_UNAVAILABLE", "No active hard-risk policy at execution time");
        }

        OrderCommand original = order.command();
        // Build once to obtain the currently active hard-risk freshness limit from server-owned state.
        RiskContext initialContext = contextFactory.build(orderId, original, now);
        Quote quote = latestQuote(order, now, initialContext.policy().marketStateMaxAgeSeconds());
        OrderCommand freshCommand = new OrderCommand(original.symbol(), original.side(), original.quantity(),
            quote.price(), original.stopLoss(), original.target(), original.signalGeneratedAt(), original.productType(),
            original.trailingPercent());
        RiskContext context = contextFactory.build(orderId, freshCommand, now);
        RiskDecisionV2 risk = riskEngine.evaluate(context);

        if (context.emergencyStopActive()) {
            throw notReady("EMERGENCY_STOP_ACTIVE", "Emergency stop is active at execution time");
        }
        if (context.marketSessionState() != MarketSessionState.OPEN) {
            throw notReady("MARKET_SESSION_CLOSED", "Market session is not open at execution time");
        }
        if (risk.decisionClass() == RiskDecisionClass.REJECTED) {
            throw notReady("RISK_REJECTED_AT_EXECUTION", "Fresh hard-risk evaluation rejected execution");
        }
        if (risk.decisionClass() == RiskDecisionClass.APPROVAL_REQUIRED
                && approval.state() == ApprovalState.AUTO_APPROVED) {
            throw notReady("RISK_REQUIRES_MANUAL_APPROVAL", "Fresh risk state now requires manual approval");
        }

        BigDecimal dailyLoss = context.realizedPnlToday().signum() < 0
            ? context.realizedPnlToday().negate() : BigDecimal.ZERO;
        ApprovalPolicyResult policyResult = policyEvaluator.evaluate(policy,
            new ApprovalPolicyInput(risk.decisionClass(), order.symbol(), risk.grossNotional(),
                risk.tradesTodayAfter(), risk.grossExposureAfter(), dailyLoss,
                now.atZone(IndianCashMarketCalendar.ZONE).toLocalTime()));

        if (policyResult.decision() == ApprovalEvaluationDecision.REJECT) {
            throw notReady("EXECUTION_POLICY_REJECTED", String.join(",", policyResult.reasonCodes()));
        }
        if (approval.state() == ApprovalState.AUTO_APPROVED
                && policyResult.decision() != ApprovalEvaluationDecision.AUTO_APPROVED) {
            throw notReady("AUTO_APPROVAL_NO_LONGER_VALID", String.join(",", policyResult.reasonCodes()));
        }

        return new ExecutionReadinessResult(freshCommand, context, risk, policy, policyResult);
    }

    private PersistedOrder loadOrder(UUID orderId) {
        String status = jdbc.sql("SELECT status FROM paper_order WHERE id = :id")
            .param("id", orderId).query(String.class).optional()
            .orElseThrow(() -> notReady("ORDER_NOT_FOUND", "Order not found: " + orderId));
        if (!"READY".equals(status)) {
            throw notReady("ORDER_NOT_READY", "Only a READY order may be revalidated for settlement");
        }
        return jdbc.sql("""
                SELECT id, symbol, side, quantity, reference_price, stop_loss, target_price, trailing_percent,
                       signal_generated_at, product_type, instrument_id, strategy, approval_id
                  FROM paper_order
                 WHERE id = :id
                """)
            .param("id", orderId)
            .query((rs, row) -> new PersistedOrder(
                rs.getObject("id", UUID.class),
                rs.getString("symbol"),
                Side.valueOf(rs.getString("side")),
                rs.getInt("quantity"),
                rs.getBigDecimal("reference_price"),
                rs.getBigDecimal("stop_loss"),
                rs.getBigDecimal("target_price"),
                rs.getBigDecimal("trailing_percent"),
                rs.getObject("signal_generated_at", OffsetDateTime.class) == null ? null
                    : rs.getObject("signal_generated_at", OffsetDateTime.class).toInstant(),
                ProductType.valueOf(rs.getString("product_type")),
                rs.getObject("instrument_id", Long.class),
                rs.getString("strategy"),
                rs.getObject("approval_id", UUID.class)))
            .optional()
            .orElseThrow(() -> notReady("ORDER_NOT_FOUND", "Order not found: " + orderId));
    }

    private Approval loadApproval(PersistedOrder order) {
        if (order.approvalId() == null) {
            throw notReady("APPROVAL_MISSING", "Order has no approval evidence");
        }
        return jdbc.sql("""
                SELECT id, policy_id, policy_version, state, expires_at
                  FROM order_approval
                 WHERE id = :id AND order_id = :orderId
                """)
            .param("id", order.approvalId())
            .param("orderId", order.id())
            .query((rs, row) -> new Approval(
                rs.getObject("id", UUID.class), rs.getObject("policy_id", UUID.class),
                rs.getInt("policy_version"), ApprovalState.valueOf(rs.getString("state")),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
            .optional()
            .orElseThrow(() -> notReady("APPROVAL_MISSING", "Approval evidence not found"));
    }

    private Quote latestQuote(PersistedOrder order, Instant now, long maxAgeSeconds) {
        if (order.instrumentId() == null) {
            throw notReady("MARKET_STATE_UNAVAILABLE", "Canonical instrument identity is required for execution");
        }
        Quote quote = jdbc.sql("""
                SELECT close_price, candle_time
                  FROM market_candle
                 WHERE instrument_id = :instrumentId AND candle_time <= :now
                 ORDER BY candle_time DESC, candle_id DESC
                 LIMIT 1
                """)
            .param("instrumentId", order.instrumentId())
            .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .query((rs, row) -> new Quote(
                rs.getBigDecimal("close_price"), rs.getObject("candle_time", OffsetDateTime.class).toInstant()))
            .optional()
            .orElseThrow(() -> notReady("MARKET_STATE_UNAVAILABLE", "No persisted market state for instrument"));
        if (quote.asOf().isAfter(now) || Duration.between(quote.asOf(), now).getSeconds() > maxAgeSeconds) {
            throw notReady("MARKET_STATE_STALE", "Latest persisted market state is stale");
        }
        return quote;
    }

    private static ExecutionNotReadyException notReady(String code, String message) {
        return new ExecutionNotReadyException(code, message);
    }

    private enum ApprovalState { PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED, AUTO_APPROVED }

    private record Approval(UUID id, UUID policyId, int policyVersion, ApprovalState state, Instant expiresAt) {}

    private record Quote(BigDecimal price, Instant asOf) {}

    private record PersistedOrder(UUID id, String symbol, Side side, int quantity, BigDecimal referencePrice,
                                  BigDecimal stopLoss, BigDecimal target, BigDecimal trailingPercent,
                                  Instant signalGeneratedAt, ProductType productType, Long instrumentId,
                                  String strategy, UUID approvalId) {
        OrderCommand command() {
            return new OrderCommand(symbol, side, quantity, referencePrice, stopLoss, target,
                signalGeneratedAt, productType, trailingPercent);
        }
    }
}
