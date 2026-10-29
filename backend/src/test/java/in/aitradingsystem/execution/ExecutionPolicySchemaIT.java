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

class ExecutionPolicySchemaIT extends AbstractPostgresIT {

    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM execution_policy WHERE created_by = 'P07_PR01_TEST'").update();
    }

    @Test
    void persistsVersionedScopedExecutionPolicy() {
        UUID id = UUID.randomUUID();
        OffsetDateTime effective = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);

        jdbc.sql("""
                INSERT INTO execution_policy (
                    id, policy_version, execution_mode, strategy, trading_style, approval_mode,
                    max_order_notional, max_daily_trades, max_gross_exposure, max_daily_loss,
                    symbol_allowlist, symbol_denylist, session_start, session_end,
                    active, effective_from, created_by)
                VALUES (
                    :id, 700001, 'PAPER', 'SMA_CROSS', 'INTRADAY', 'HYBRID',
                    25000.00, 8, 100000.00, 5000.00,
                    '[\"RELIANCE\",\"TCS\"]'::jsonb, '[\"ILLQ\"]'::jsonb, '09:20', '15:15',
                    TRUE, :effective, 'P07_PR01_TEST')
                """)
            .param("id", id)
            .param("effective", effective)
            .update();

        var row = jdbc.sql("""
                SELECT policy_version, execution_mode, strategy, trading_style, approval_mode,
                       active, symbol_allowlist::text AS allowlist
                  FROM execution_policy
                 WHERE id = :id
                """)
            .param("id", id)
            .query((rs, n) -> new PolicyRow(
                rs.getInt("policy_version"),
                rs.getString("execution_mode"),
                rs.getString("strategy"),
                rs.getString("trading_style"),
                rs.getString("approval_mode"),
                rs.getBoolean("active"),
                rs.getString("allowlist")))
            .single();

        assertThat(row.version()).isEqualTo(700001);
        assertThat(row.executionMode()).isEqualTo("PAPER");
        assertThat(row.strategy()).isEqualTo("SMA_CROSS");
        assertThat(row.tradingStyle()).isEqualTo("INTRADAY");
        assertThat(row.approvalMode()).isEqualTo("HYBRID");
        assertThat(row.active()).isTrue();
        assertThat(row.allowlist()).contains("RELIANCE", "TCS");
    }

    @Test
    void rejectsInvalidEnumsLimitsJsonAndTimeRanges() {
        assertRejected(700010, "SHADOW", "ANY", "MANUAL", "100", "[]", "[]", null, null);
        assertRejected(700011, "PAPER", "DELIVERY", "MANUAL", "100", "[]", "[]", null, null);
        assertRejected(700012, "PAPER", "ANY", "REVIEW", "100", "[]", "[]", null, null);
        assertRejected(700013, "PAPER", "ANY", "MANUAL", "0", "[]", "[]", null, null);
        assertRejected(700014, "PAPER", "ANY", "MANUAL", "100", "{}", "[]", null, null);
        assertRejected(700015, "PAPER", "ANY", "MANUAL", "100", "[]", "[]", "15:00", "09:30");
        assertRejected(700016, "PAPER", "ANY", "MANUAL", "100", "[]", "[]", "09:30", null);
    }

    @Test
    void policyVersionIsImmutableIdentityAndMustBeUnique() {
        insertMinimal(700020, UUID.randomUUID());
        assertThatThrownBy(() -> insertMinimal(700020, UUID.randomUUID()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertRejected(int version, String mode, String style, String approval, String notional,
                                String allowlist, String denylist, String start, String end) {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO execution_policy (
                    id, policy_version, execution_mode, trading_style, approval_mode,
                    max_order_notional, max_daily_trades, max_gross_exposure, max_daily_loss,
                    symbol_allowlist, symbol_denylist, session_start, session_end,
                    active, effective_from, created_by)
                VALUES (
                    :id, :version, :mode, :style, :approval,
                    CAST(:notional AS NUMERIC), 1, 1000.00, 100.00,
                    CAST(:allowlist AS JSONB), CAST(:denylist AS JSONB), CAST(:start AS TIME), CAST(:end AS TIME),
                    FALSE, now(), 'P07_PR01_TEST')
                """)
            .param("id", UUID.randomUUID())
            .param("version", version)
            .param("mode", mode)
            .param("style", style)
            .param("approval", approval)
            .param("notional", notional)
            .param("allowlist", allowlist)
            .param("denylist", denylist)
            .param("start", start)
            .param("end", end)
            .update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertMinimal(int version, UUID id) {
        jdbc.sql("""
                INSERT INTO execution_policy (
                    id, policy_version, execution_mode, trading_style, approval_mode,
                    max_order_notional, max_daily_trades, max_gross_exposure, max_daily_loss,
                    active, effective_from, created_by)
                VALUES (:id, :version, 'PAPER', 'ANY', 'MANUAL', 1000.00, 1, 5000.00, 500.00,
                        FALSE, now(), 'P07_PR01_TEST')
                """)
            .param("id", id)
            .param("version", version)
            .update();
    }

    private record PolicyRow(int version, String executionMode, String strategy,
                             String tradingStyle, String approvalMode, boolean active, String allowlist) {}
}
