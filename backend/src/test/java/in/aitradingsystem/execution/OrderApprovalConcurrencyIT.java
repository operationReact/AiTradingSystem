package in.aitradingsystem.execution;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class OrderApprovalConcurrencyIT extends AbstractPostgresIT {
    private static final int POLICY_VERSION = 740001;

    @Autowired JdbcClient jdbc;
    @Autowired OrderApprovalService approvals;

    private UUID policyId;
    private UUID orderId;
    private UUID riskDecisionId;
    private UUID approvalId;

    @BeforeEach
    void setup() {
        policyId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        riskDecisionId = UUID.randomUUID();
        approvalId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO execution_policy (
                    id, policy_version, execution_mode, trading_style, approval_mode,
                    max_order_notional, max_daily_trades, max_gross_exposure, max_daily_loss,
                    active, effective_from, created_by)
                VALUES (:id, :version, 'PAPER', 'ANY', 'MANUAL', 10000, 10, 50000, 5000,
                        TRUE, now() - interval '1 hour', 'P07_PR05_TEST')
                """)
            .param("id", policyId).param("version", POLICY_VERSION).update();
        jdbc.sql("""
                INSERT INTO paper_order (
                    id, idempotency_key, request_hash, symbol, side, quantity, reference_price,
                    stop_loss, target_price, signal_generated_at, status, created_at, updated_at,
                    submitted_quantity, filled_quantity, order_type, product_type)
                VALUES (:id, :key, :hash, 'TCS', 'BUY', 1, 100, 95, 110, now(), 'APPROVAL_PENDING', now(), now(),
                        1, 0, 'MARKET', 'DELIVERY')
                """)
            .param("id", orderId).param("key", "p07-concurrency-" + orderId)
            .param("hash", "0".repeat(64)).update();
        jdbc.sql("""
                INSERT INTO risk_decision (
                    id, order_id, decision, reason_codes, risk_budget, estimated_risk,
                    estimated_notional, available_cash, open_positions, realized_pnl_today,
                    daily_loss_limit, policy_version, evaluated_at, result_class)
                VALUES (:id, :orderId, 'APPROVED', '[]'::jsonb, 100, 5, 100, 100000, 0, 0, 5000,
                        'risk-policy-v1', now(), 'APPROVED')
                """)
            .param("id", riskDecisionId).param("orderId", orderId).update();
        OffsetDateTime requested = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(10);
        jdbc.sql("""
                INSERT INTO order_approval (
                    id, order_id, policy_id, policy_version, state, requested_at, expires_at,
                    row_version, risk_decision_id, request_snapshot)
                VALUES (:id, :orderId, :policyId, :version, 'PENDING', :requested, :expires,
                        0, :riskId, '{}'::jsonb)
                """)
            .param("id", approvalId).param("orderId", orderId).param("policyId", policyId)
            .param("version", POLICY_VERSION).param("requested", requested)
            .param("expires", requested.plusMinutes(5)).param("riskId", riskDecisionId).update();
        jdbc.sql("UPDATE paper_order SET approval_id = :approvalId WHERE id = :orderId")
            .param("approvalId", approvalId).param("orderId", orderId).update();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("UPDATE paper_order SET approval_id = NULL WHERE id = :id").param("id", orderId).update();
        jdbc.sql("DELETE FROM order_approval WHERE id = :id").param("id", approvalId).update();
        jdbc.sql("DELETE FROM risk_decision_rule WHERE risk_decision_id = :id").param("id", riskDecisionId).update();
        jdbc.sql("DELETE FROM risk_decision WHERE id = :id").param("id", riskDecisionId).update();
        jdbc.sql("DELETE FROM paper_order WHERE id = :id").param("id", orderId).update();
        jdbc.sql("DELETE FROM execution_policy WHERE id = :id").param("id", policyId).update();
    }

    @Test
    void onlyOneConcurrentApproverCanWinAndLoserReturnsCurrentState() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<OrderApprovalView> approve = () -> approvals.decide(
                approvalId, 0, OrderApprovalState.APPROVED, "approver-a", "approve");
            Callable<OrderApprovalView> reject = () -> approvals.decide(
                approvalId, 0, OrderApprovalState.REJECTED, "approver-b", "reject");

            List<Future<OrderApprovalView>> futures = pool.invokeAll(List.of(approve, reject));
            OrderApprovalView first = futures.get(0).get();
            OrderApprovalView second = futures.get(1).get();
            OrderApprovalView persisted = approvals.require(approvalId);

            assertThat(persisted.rowVersion()).isEqualTo(1);
            assertThat(persisted.state()).isIn(OrderApprovalState.APPROVED, OrderApprovalState.REJECTED);
            assertThat(persisted.decidedBy()).isIn("approver-a", "approver-b");
            assertThat(first.state()).isEqualTo(persisted.state());
            assertThat(second.state()).isEqualTo(persisted.state());
            assertThat(first.rowVersion()).isEqualTo(1);
            assertThat(second.rowVersion()).isEqualTo(1);
        }
    }

    @Test
    void duplicateClickReturnsCurrentStateWithoutSecondMutation() {
        OrderApprovalView winner = approvals.decide(
            approvalId, 0, OrderApprovalState.APPROVED, "approver-a", "approve");
        OrderApprovalView duplicate = approvals.decide(
            approvalId, 0, OrderApprovalState.APPROVED, "approver-a", "approve-again");

        assertThat(winner.state()).isEqualTo(OrderApprovalState.APPROVED);
        assertThat(duplicate.state()).isEqualTo(OrderApprovalState.APPROVED);
        assertThat(duplicate.rowVersion()).isEqualTo(1);
        assertThat(duplicate.decisionReason()).isEqualTo("approve");
    }
}
