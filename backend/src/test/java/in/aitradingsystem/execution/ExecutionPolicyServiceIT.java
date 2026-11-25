package in.aitradingsystem.execution;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.risk.ExecutionMode;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ExecutionPolicyServiceIT extends AbstractPostgresIT {

    @Autowired JdbcClient jdbc;
    @Autowired ExecutionPolicyService policies;

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM execution_policy WHERE created_by = 'P07_PR03_TEST'").update();
    }

    @Test
    void resolvesCanonicalExactThenStrategyAnyThenDefaultStyleThenDefaultAnyPrecedence() {
        UUID defaultAny = insert(720001, null, "ANY");
        UUID defaultStyle = insert(720002, null, "INTRADAY");
        UUID strategyAny = insert(720003, "ORB", "ANY");
        UUID exact = insert(720004, "ORB", "INTRADAY");

        assertThat(resolve()).extracting(ExecutionPolicy::id).isEqualTo(exact);

        deactivate(exact);
        assertThat(resolve()).extracting(ExecutionPolicy::id).isEqualTo(strategyAny);

        deactivate(strategyAny);
        assertThat(resolve()).extracting(ExecutionPolicy::id).isEqualTo(defaultStyle);

        deactivate(defaultStyle);
        assertThat(resolve()).extracting(ExecutionPolicy::id).isEqualTo(defaultAny);
    }

    @Test
    void ignoresInactiveExpiredAndDifferentModePolicies() {
        UUID eligible = insert(720010, null, "ANY");
        UUID inactive = insert(720011, "ORB", "INTRADAY");
        deactivate(inactive);
        insertExpired(720012, "ORB", "INTRADAY");
        insertLive(720013, "ORB", "INTRADAY");

        assertThat(resolve()).extracting(ExecutionPolicy::id).isEqualTo(eligible);
    }

    @Test
    void changingModeCreatesNewVersionAndPreservesHistoricalApprovalProvenance() {
        UUID originalId = insert(720020, "ORB", "INTRADAY");
        ExecutionPolicy original = policies.requireById(originalId);

        ExecutionPolicy replacement = policies.updateApprovalMode(originalId, ApprovalMode.AUTO);

        assertThat(replacement.id()).isNotEqualTo(originalId);
        assertThat(replacement.policyVersion()).isGreaterThan(original.policyVersion());
        assertThat(replacement.approvalMode()).isEqualTo(ApprovalMode.AUTO);
        assertThat(replacement.active()).isTrue();
        assertThat(replacement.maxOrderNotional()).isEqualByComparingTo(original.maxOrderNotional());
        assertThat(policies.requireById(originalId).approvalMode()).isEqualTo(ApprovalMode.HYBRID);
        assertThat(policies.requireById(originalId).active()).isFalse();
        assertThat(policies.requireById(originalId).effectiveTo()).isNotNull();
        assertThat(resolve().id()).isEqualTo(replacement.id());
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE event_type = 'APPROVAL_POLICY_UPDATED' AND entity_id = :id")
            .param("id", replacement.id().toString()).query(Long.class).single()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> policies.updateApprovalMode(originalId, ApprovalMode.MANUAL))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unchangedModeDoesNotCreateVersionOrAudit() {
        UUID originalId = insert(720021, "ORB", "INTRADAY");
        ExecutionPolicy unchanged = policies.updateApprovalMode(originalId, ApprovalMode.HYBRID);
        assertThat(unchanged.id()).isEqualTo(originalId);
        assertThat(unchanged.policyVersion()).isEqualTo(720021);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE event_type = 'APPROVAL_POLICY_UPDATED' AND entity_id = :id")
            .param("id", originalId.toString()).query(Long.class).single()).isZero();
    }

    private ExecutionPolicy resolve() {
        return policies.requirePolicy(ExecutionMode.PAPER, "ORB", ExecutionPolicyTradingStyle.INTRADAY);
    }

    private UUID insert(int version, String strategy, String style) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO execution_policy (
                    id, policy_version, execution_mode, strategy, trading_style, approval_mode,
                    max_order_notional, max_daily_trades, max_gross_exposure, max_daily_loss,
                    active, effective_from, created_by)
                VALUES (:id, :version, 'PAPER', :strategy, :style, 'HYBRID',
                        10000, 10, 50000, 5000, TRUE, now() - interval '1 hour', 'P07_PR03_TEST')
                """)
            .param("id", id).param("version", version)
            .param("strategy", strategy, java.sql.Types.VARCHAR).param("style", style).update();
        return id;
    }

    private void insertExpired(int version, String strategy, String style) {
        UUID id = insert(version, strategy, style);
        jdbc.sql("UPDATE execution_policy SET effective_to = now() - interval '1 minute' WHERE id = :id")
            .param("id", id).update();
    }

    private void insertLive(int version, String strategy, String style) {
        UUID id = insert(version, strategy, style);
        jdbc.sql("UPDATE execution_policy SET execution_mode = 'LIVE' WHERE id = :id").param("id", id).update();
    }

    private void deactivate(UUID id) {
        jdbc.sql("UPDATE execution_policy SET active = FALSE WHERE id = :id").param("id", id).update();
    }
}
