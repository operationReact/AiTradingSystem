package in.aitradingsystem.paper;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.common.IdempotencyConflictException;
import in.aitradingsystem.domain.OrderStatus;
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

    public PaperOrderService(PaperOrderRepository orders, PaperAccountRepository accounts,
                             RiskContextFactory contextFactory, RiskPolicyEngine riskEngine,
                             RiskDecisionPersistenceService decisions, PaperExecutionEngine execution,
                             AuditService audit, Clock clock) {
        this.orders = orders;
        this.accounts = accounts;
        this.contextFactory = contextFactory;
        this.riskEngine = riskEngine;
        this.decisions = decisions;
        this.execution = execution;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public OrderResult placeOrder(OrderCommand command, String idempotencyKey) {
        String requestHash = command.canonicalHash();
        Optional<ExistingOrder> existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) return replay(existing.get(), requestHash, idempotencyKey);

        AccountSnapshot account = accounts.lock();
        existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) return replay(existing.get(), requestHash, idempotencyKey);

        UUID orderId = UUID.randomUUID();
        Instant now = clock.instant();
        orders.insert(orderId, idempotencyKey, requestHash, command, now);

        RiskContext context = contextFactory.build(orderId, command, now);
        orders.enrichIdentity(orderId, context, now);
        RiskDecisionV2 decision = riskEngine.evaluate(context);
        decisions.persist(orderId, context, decision);

        if (decision.decisionClass() != RiskDecisionClass.APPROVED) {
            String reasons = reasonsText(decision.ruleResults());
            orders.transition(orderId, OrderStatus.NEW, OrderStatus.REJECTED, reasons, now);
            audit.record("ORDER_REJECTED", "PAPER_ORDER", orderId.toString(),
                Map.of("symbol", command.symbol(), "side", command.side().name(),
                    "productType", command.productType().name(),
                    "decisionClass", decision.decisionClass().name(), "reasons", reasons));
            return orders.loadResult(orderId, false);
        }

        orders.transition(orderId, OrderStatus.NEW, OrderStatus.RISK_APPROVED, null, now);
        orders.transition(orderId, OrderStatus.RISK_APPROVED, OrderStatus.READY, null, now);
        execution.execute(orderId, command, context, decision, account, now);
        orders.transition(orderId, OrderStatus.READY, OrderStatus.FILLED, null, now);
        audit.record("ORDER_FILLED", "PAPER_ORDER", orderId.toString(),
            Map.of("symbol", command.symbol(), "side", command.side().name(), "quantity", command.quantity(),
                "productType", command.productType().name(),
                "fillPrice", decision.effectiveFillPrice(), "policyVersion", decision.policyVersion()));
        return orders.loadResult(orderId, false);
    }

    public OrderResult loadResult(UUID orderId, boolean replayed) {
        return orders.loadResult(orderId, replayed);
    }

    private OrderResult replay(ExistingOrder existing, String requestHash, String idempotencyKey) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
        return orders.loadResult(existing.id(), true);
    }

    static String reasonsText(java.util.List<RiskRuleResult> results) {
        return results.stream()
            .filter(result -> result.outcome() != RiskOutcome.PASS)
            .map(RiskRuleResult::reasonCode)
            .collect(Collectors.joining(","));
    }
}
