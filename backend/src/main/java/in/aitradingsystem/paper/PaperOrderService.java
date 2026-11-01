package in.aitradingsystem.paper;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.common.IdempotencyConflictException;
import in.aitradingsystem.domain.OrderStatus;
import in.aitradingsystem.domain.ProductType;
import in.aitradingsystem.execution.ApprovalEvaluationDecision;
import in.aitradingsystem.execution.ApprovalPolicyEvaluator;
import in.aitradingsystem.execution.ApprovalPolicyInput;
import in.aitradingsystem.execution.ExecutionNotReadyException;
import in.aitradingsystem.execution.ExecutionPolicyService;
import in.aitradingsystem.execution.ExecutionPolicyTradingStyle;
import in.aitradingsystem.execution.ExecutionReadinessResult;
import in.aitradingsystem.execution.ExecutionReadinessService;
import in.aitradingsystem.execution.OrderApprovalService;
import in.aitradingsystem.execution.OrderApprovalState;
import in.aitradingsystem.execution.OrderApprovalView;
import in.aitradingsystem.marketdata.IndianCashMarketCalendar;
import in.aitradingsystem.risk.ExecutionMode;
import in.aitradingsystem.paper.PaperAccountRepository.AccountSnapshot;
import in.aitradingsystem.paper.PaperOrderRepository.ExistingOrder;
import in.aitradingsystem.paper.PaperViews.OrderResult;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskContextFactory;
import in.aitradingsystem.risk.RiskDecisionClass;
import in.aitradingsystem.risk.RiskDecisionPersistenceService;
import in.aitradingsystem.risk.RiskDecisionV2;
import in.aitradingsystem.risk.RiskOutcome;
import in.aitradingsystem.risk.RiskPolicyEngine;
import in.aitradingsystem.risk.RiskRuleResult;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transaction orchestration for PAPER orders. Persistence and execution mechanics live behind dedicated services. */
@Service
public class PaperOrderService {
    private final PaperOrderRepository orders;
    private final PaperAccountRepository accounts;
    private final RiskContextFactory contextFactory;
    private final RiskPolicyEngine riskEngine;
    private final RiskDecisionPersistenceService decisions;
    private final PaperExecutionEngine execution;
    private final AuditService audit;
    private final Clock clock;
    private final ExecutionPolicyService policies;
    private final ApprovalPolicyEvaluator policyEvaluator;
    private final OrderApprovalService approvals;
    private final ExecutionReadinessService readiness;

    public PaperOrderService(PaperOrderRepository orders, PaperAccountRepository accounts,
                             RiskContextFactory contextFactory, RiskPolicyEngine riskEngine,
                             RiskDecisionPersistenceService decisions, PaperExecutionEngine execution,
                             AuditService audit, Clock clock, ExecutionPolicyService policies,
                             ApprovalPolicyEvaluator policyEvaluator, OrderApprovalService approvals,
                             ExecutionReadinessService readiness) {
        this.orders = orders;
        this.accounts = accounts;
        this.contextFactory = contextFactory;
        this.riskEngine = riskEngine;
        this.decisions = decisions;
        this.execution = execution;
        this.audit = audit;
        this.clock = clock;
        this.policies = policies;
        this.policyEvaluator = policyEvaluator;
        this.approvals = approvals;
        this.readiness = readiness;
    }

    @Transactional
    public OrderResult placeOrder(OrderCommand command, String idempotencyKey) {
        TrailingProtectionPolicy.validate(command.side(), command.trailingStopPercent());
        String requestHash = command.canonicalHash();
        Optional<ExistingOrder> existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) return replay(existing.get(), requestHash, idempotencyKey);

        AccountSnapshot account = accounts.lock();
        expireDue();
        existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) return replay(existing.get(), requestHash, idempotencyKey);

        UUID orderId = UUID.randomUUID();
        Instant now = clock.instant();
        orders.insert(orderId, idempotencyKey, requestHash, command, now);

        RiskContext context = contextFactory.build(orderId, command, now);
        orders.enrichIdentity(orderId, context, now);
        RiskDecisionV2 decision = riskEngine.evaluate(context);
        decisions.persist(orderId, context, decision);

        if (decision.decisionClass() == RiskDecisionClass.REJECTED) {
            String reasons = reasonsText(decision.ruleResults());
            orders.transition(orderId, OrderStatus.NEW, OrderStatus.REJECTED, reasons, now);
            audit.record("ORDER_REJECTED", "PAPER_ORDER", orderId.toString(),
                Map.of("symbol", command.symbol(), "side", command.side().name(),
                    "productType", command.productType().name(),
                    "decisionClass", decision.decisionClass().name(), "reasons", reasons));
            return orders.loadResult(orderId, false);
        }

        var style = command.productType() == ProductType.INTRADAY
            ? ExecutionPolicyTradingStyle.INTRADAY : ExecutionPolicyTradingStyle.SWING;
        var policy = policies.resolvePolicy(ExecutionMode.PAPER, context.strategy(), style);
        if (policy.isEmpty()) {
            rejectPolicy(orderId, "EXECUTION_POLICY_UNAVAILABLE", now);
            return orders.loadResult(orderId, false);
        }
        var result = policyEvaluator.evaluate(policy.get(), new ApprovalPolicyInput(decision.decisionClass(),
            command.symbol(), decision.grossNotional(), decision.tradesTodayAfter(), decision.grossExposureAfter(),
            context.realizedPnlToday().min(java.math.BigDecimal.ZERO).negate(),
            now.atZone(IndianCashMarketCalendar.ZONE).toLocalTime()));
        if (result.decision() == ApprovalEvaluationDecision.REJECT) {
            rejectPolicy(orderId, String.join(",", result.reasonCodes()), now);
            return orders.loadResult(orderId, false);
        }
        orders.transition(orderId, OrderStatus.NEW, OrderStatus.RISK_APPROVED, null, now);
        var approval = approvals.request(orderId, command, context, decision, policy.get(), result);
        if (approval.state() == OrderApprovalState.PENDING) {
            orders.transition(orderId, OrderStatus.RISK_APPROVED, OrderStatus.APPROVAL_PENDING, null, now);
        } else {
            orders.transition(orderId, OrderStatus.RISK_APPROVED, OrderStatus.READY, null, now);
            settle(orderId, account);
        }
        return orders.loadResult(orderId, false);
    }

    /** The same account lock serializes new orders, decisions, cancellations and settlement, in that order. */
    @Transactional
    public OrderApprovalView decideApproval(UUID approvalId, int version, OrderApprovalState decision,
                                            String actor, String reason) {
        AccountSnapshot account = accounts.lock();
        var before = approvals.require(approvalId);
        OrderStatus status = orders.statusForUpdate(before.orderId());
        if (status != OrderStatus.APPROVAL_PENDING) return before;
        var result = approvals.decide(approvalId, version, decision, actor, reason);
        Instant now = clock.instant();
        switch (result.state()) {
            case APPROVED -> {
                orders.transition(result.orderId(), status, OrderStatus.READY, null, now);
                settle(result.orderId(), account);
            }
            case REJECTED -> orders.transition(result.orderId(), status, OrderStatus.REJECTED, result.decisionReason(), now);
            case CANCELLED -> orders.transition(result.orderId(), status, OrderStatus.CANCELLED, result.decisionReason(), now);
            case EXPIRED -> orders.transition(result.orderId(), status, OrderStatus.EXPIRED, result.decisionReason(), now);
            default -> { }
        }
        return result;
    }

    private void rejectPolicy(UUID orderId, String reason, Instant now) {
        orders.transition(orderId, OrderStatus.NEW, OrderStatus.REJECTED, reason, now);
        audit.record("ORDER_REJECTED", "PAPER_ORDER", orderId.toString(), Map.of("reasons", reason));
    }

    @Transactional
    public void expirePendingApprovals() {
        accounts.lock();
        expireDue();
    }

    private void expireDue() {
        for (UUID id : approvals.duePending()) {
            var current = approvals.require(id);
            decideApproval(id, current.rowVersion(), OrderApprovalState.REJECTED, "SYSTEM", "APPROVAL_EXPIRED");
        }
    }

    @Transactional
    public OrderResult cancel(UUID orderId) {
        accounts.lock();
        OrderStatus status = orders.statusForUpdate(orderId);
        if (status == OrderStatus.CANCELLED || status == OrderStatus.REJECTED || status == OrderStatus.EXPIRED
                || status == OrderStatus.FILLED) return orders.loadResult(orderId, false);
        Instant now = clock.instant();
        if (status == OrderStatus.APPROVAL_PENDING) {
            orders.approvalId(orderId).ifPresent(id -> {
                var current = approvals.require(id);
                approvals.decide(id, current.rowVersion(), OrderApprovalState.CANCELLED, "SYSTEM", "ORDER_CANCELLED");
            });
        }
        orders.transition(orderId, status, OrderStatus.CANCELLED, "ORDER_CANCELLED", now);
        return orders.loadResult(orderId, false);
    }

    public OrderResult loadResult(UUID orderId, boolean replayed) {
        return orders.loadResult(orderId, replayed);
    }

    private OrderResult replay(ExistingOrder existing, String requestHash, String idempotencyKey) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException("Idempotency-Key already used with a different PAPER order payload: " + idempotencyKey);
        }
        return orders.loadResult(existing.id(), true);
    }

    private void settle(UUID orderId, AccountSnapshot account) {
        var result = orders.loadResult(orderId, false);
        if (result.order() == null) return;
        OrderStatus current = OrderStatus.valueOf(result.order().status());
        if (current != OrderStatus.READY) return;
        OrderCommand command = new OrderCommand(
            result.order().symbol(),
            in.aitradingsystem.domain.Side.valueOf(result.order().side()),
            result.order().quantity(),
            result.order().entryPrice(),
            result.order().stopLoss(),
            result.order().target(),
            result.order().signalGeneratedAt(),
            ProductType.valueOf(result.order().productType()),
            result.order().trailingStopPercent());
        RiskContext context = contextFactory.build(orderId, command, clock.instant());
        ExecutionReadinessResult readinessResult = readiness.evaluate(context, clock.instant());
        if (!readinessResult.ready()) {
            throw new ExecutionNotReadyException(readinessResult.reasonCodes());
        }
        RiskDecisionV2 decision = riskEngine.evaluate(context);
        if (decision.outcome() != RiskOutcome.APPROVED) {
            throw new ExecutionNotReadyException(
                decision.ruleResults().stream().filter(r -> !r.passed()).map(RiskRuleResult::reasonCode).toList());
        }
        execution.execute(orderId, command, context, decision, account, clock.instant());
        orders.transition(orderId, current, OrderStatus.FILLED, null, clock.instant());
    }

    private static String reasonsText(java.util.List<RiskRuleResult> results) {
        return results.stream().filter(r -> !r.passed()).map(RiskRuleResult::reasonCode).collect(Collectors.joining(","));
    }
}
