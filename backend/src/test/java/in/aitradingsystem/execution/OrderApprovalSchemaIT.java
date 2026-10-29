package in.aitradingsystem.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class OrderApprovalSchemaIT extends AbstractPostgresIT {

    @Autowired JdbcClient jdbc;

    private UUID orderId;
    private UUID policyId;
    private UUID decisionId;

    @AfterEach
    void cleanup() {
        if (orderId != null) {
            jdbc.sql("UPDATE paper_order SET approval_id = NULL WHERE id = :id").param("id", orderId).update();
            jdbc.sql("DELETE FROM order_approval WHERE order_id = :id").param("id", orderId).update();
            jdbc.sql("DELETE FROM risk_decision WHERE order_id = :id").param("id", orderId).update();
            jdbc.sql("DELETE FROM paper_order WHERE id = :id").param("id", orderId).update();
        }
        if (policyId != null) {
            jdbc.sql("DELETE FROM execution_policy WHERE id = :id").param("id", policyId).update();
        }
    }

    @Test
    void persistsApprovalWithExactPolicyRiskAndRequestSnapshotProvenance() {
        Fixture fixture = fixture(710001);
        UUID approvalId = UUID.randomUUID();
        OffsetDateTime requested = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);

        jdbc.sql("""
                INSERT INTO order_approval (
                    id, order_id, policy_id, policy_version, state, decision_reason,
                    requested_at, expires_at, row_version, risk_decision_id, request_snapshot)
                VALUES (
                    :id, :orderId, :policyId, :version, 'PENDING', NULL,
                    :requested, :expires, 0, :riskId, CAST(:snapshot AS JSONB))
                """)
            .param("id", approvalId)
            .param("orderId", fixture.orderId())
            .param("policyId", fixture.policyId())
            .param("version", fixture.policyVersion())
            .param("requested", requested)
            .param("expires", requested.plusMinutes(5))
            .param("riskId", fixture.riskDecisionId())
            .param("snapshot", "{\"symbol\":\"TCS\",\"side\":\"BUY\",\"quantity\":1}")
            .update();

        jdbc.sql("UPDATE paper_order SET approval_id = :approvalId WHERE id = :orderId")
            .param("approvalId", approvalId)
            .param("orderId", fixture.orderId())
            .update();

        var row = jdbc.sql("""
                SELECT a.state, a.policy_version, a.row_version, a.request_snapshot::text AS snapshot,
                       o.approval_id
                  FROM order_approval a
                  JOIN paper_order o ON o.id = a.order_id
                 WHERE a.id = :id
                """)
            .param("id", approvalId)
            .query((rs, n) -> new ApprovalRow(
                rs.getString("state"), rs.getInt("policy_version"), rs.getInt("row_version"),
                rs.getString("snapshot"), rs.getObject("approval_id", UUID.class)))
            .single();

        assertThat(row.state()).isEqualTo("PENDING");
        assertThat(row.policyVersion()).isEqualTo(710001);
        assertThat(row.rowVersion()).isZero();
        assertThat(row.snapshot()).contains("TCS", "quantity");
        assertThat(row.approvalId()).isEqualTo(approvalId);
    }

    @Test
    void enforcesOneApprovalPerOrderAndCanonicalStateExpirySnapshotConstraints() {
        Fixture fixture = fixture(710002);
        OffsetDateTime requested = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        insertApproval(UUID.randomUUID(), fixture, "PENDING", requested, requested.plusMinutes(5), "{}");

        assertThatThrownBy(() -> insertApproval(UUID.randomUUID(), fixture, "PENDING", requested, requested.plusMinutes(5), "{}"))
            .isInstanceOf(DataIntegrityViolationException.class);

        Fixture second = replaceOrderAndDecision(710002);
        assertThatThrownBy(() -> insertApproval(UUID.randomUUID(), second, "WAITING", requested, requested.plusMinutes(5), "{}"))
            .isInstanceOf(DataIntegrityViolationException.class);
        cleanupOrderOnly(second.orderId());

        Fixture third = replaceOrderAndDecision(710002);
        assertThatThrownBy(() -> insertApproval(UUID.randomUUID(), third, "PENDING", requested, requested.minusSeconds(1), "{}"))
            .isInstanceOf(DataIntegrityViolationException.class);
        cleanupOrderOnly(third.orderId());

        Fixture fourth = replaceOrderAndDecision(710002);
        assertThatThrownBy(() -> insertApproval(UUID.randomUUID(), fourth, "PENDING", requested, requested.plusMinutes(5), "[]"))
            .isInstanceOf(DataIntegrityViolationException.class);
        cleanupOrderOnly(fourth.orderId());
    }

    private void insertApproval(UUID id, Fixture fixture, String state, OffsetDateTime requested,
                                OffsetDateTime expires, String snapshot) {
        jdbc.sql("""
                INSERT INTO order_approval (
                    id, order_id, policy_id, policy_version, state, requested_at, expires_at,
                    risk_decision_id, request_snapshot)
                VALUES (:id, :orderId, :policyId, :version, :state, :requested, :expires,
                        :riskId, CAST(:snapshot AS JSONB))
                """)
            .param("id", id)
            .param("orderId", fixture.orderId())
            .param("policyId", fixture.policyId())
            .param("version", fixture.policyVersion())
            .param("state", state)
            .param("requested", requested)
            .param("expires", expires)
            .param("riskId", fixture.riskDecisionId())
            .param("snapshot", snapshot)
            .update();
    }

    private Fixture fixture(int version) {
        policyId = UUID.randomUUID();
        createPolicy(policyId, version);
        return replaceOrderAndDecision(version);
    }

    private Fixture replaceOrderAndDecision(int version) {
        orderId = UUID.randomUUID();
        decisionId = UUID.randomUUID();
        createOrder(orderId);
        createRiskDecision(decisionId, orderId);
        return new Fixture(orderId, policyId, version, decisionId);
    }

    private void createPolicy(UUID id, int version) {
        jdbc.sql("""
                INSERT INTO execution_policy (
                    id, policy_version, execution_mode, trading_style, approval_mode,
                    max_order_notional, max_daily_trades, max_gross_exposure, max_daily_loss,
                    active, effective_from, created_by)
                VALUES (:id, :version, 'PAPER', 'ANY', 'MANUAL', 1000, 5, 5000, 500,
                        TRUE, now(), 'P07_PR02_TEST')
                """)
            .param("id", id).param("version", version).update();
    }

    private void createOrder(UUID id) {
        jdbc.sql("""
                INSERT INTO paper_order (
                    id, idempotency_key, request_hash, symbol, side, quantity, reference_price,
                    stop_loss, target_price, signal_generated_at, status, created_at, updated_at,
                    submitted_quantity, filled_quantity, order_type, product_type)
                VALUES (:id, :key, :hash, 'TCS', 'BUY', 1, 100, 95, 110, now(), 'RISK_APPROVED', now(), now(),
                        1, 0, 'MARKET', 'DELIVERY')
                """)
            .param("id", id)
            .param("key", "p07-approval-" + id)
            .param("hash", "0".repeat(64))
            .update();
    }

    private void createRiskDecision(UUID id, UUID order) {
        jdbc.sql("""
                INSERT INTO risk_decision (
                    id, order_id, decision, reason_codes, risk_budget, estimated_risk,
                    estimated_notional, available_cash, open_positions, realized_pnl_today,
                    daily_loss_limit, policy_version, evaluated_at, result_class)
                VALUES (:id, :orderId, 'APPROVED', '[]'::jsonb, 100, 5, 100, 10000, 0, 0, 500,
                        'risk-policy-v1', now(), 'APPROVED')
                """)
            .param("id", id).param("orderId", order).update();
    }

    private void cleanupOrderOnly(UUID id) {
        jdbc.sql("DELETE FROM order_approval WHERE order_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM risk_decision WHERE order_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM paper_order WHERE id = :id").param("id", id).update();
    }

    private record Fixture(UUID orderId, UUID policyId, int policyVersion, UUID riskDecisionId) {}
    private record ApprovalRow(String state, int policyVersion, int rowVersion, String snapshot, UUID approvalId) {}
}
