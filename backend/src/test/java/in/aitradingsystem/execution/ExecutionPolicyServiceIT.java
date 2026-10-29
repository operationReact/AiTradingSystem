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
