package in.aitradingsystem.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.common.NotFoundException;
import in.aitradingsystem.risk.ExecutionMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionPolicyService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final AuditService audit;

    public ExecutionPolicyService(JdbcClient jdbc, ObjectMapper json, Clock clock, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.audit = audit;
    }

    public List<ExecutionPolicy> listPolicies() {
        return jdbc.sql("SELECT * FROM execution_policy ORDER BY policy_version DESC")
            .query((rs, rowNum) -> map(rs))
            .list();
    }

    @Transactional
    public ExecutionPolicy updateApprovalMode(UUID policyId, ApprovalMode approvalMode) {
        if (policyId == null) throw new IllegalArgumentException("policyId is required");
        if (approvalMode == null) throw new IllegalArgumentException("approvalMode is required");
        // Serial version allocation also protects simultaneous updates of the same source policy.
        jdbc.sql("LOCK TABLE execution_policy IN SHARE ROW EXCLUSIVE MODE").update();
        ExecutionPolicy previous = requireById(policyId);
        if (!previous.active()) throw new IllegalArgumentException("Policy is inactive; refresh before changing approval mode");
        if (previous.approvalMode() == approvalMode) return previous;
        int version = jdbc.sql("SELECT COALESCE(max(policy_version), 0) + 1 FROM execution_policy")
            .query(Integer.class).single();
        UUID id = UUID.randomUUID();
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.sql("UPDATE execution_policy SET active = FALSE, effective_to = :now WHERE id = :id")
            .param("now", now).param("id", policyId).update();
        jdbc.sql("""
            INSERT INTO execution_policy (id, policy_version, execution_mode, strategy, trading_style,
                approval_mode, max_order_notional, max_daily_trades, max_gross_exposure, max_daily_loss,
                symbol_allowlist, symbol_denylist, session_start, session_end, active, effective_from, created_by)
            SELECT :id, :version, execution_mode, strategy, trading_style, :mode,
                max_order_notional, max_daily_trades, max_gross_exposure, max_daily_loss,
                symbol_allowlist, symbol_denylist, session_start, session_end, TRUE, :now, created_by
              FROM execution_policy WHERE id = :previous
            """).param("id", id).param("version", version).param("mode", approvalMode.name())
            .param("now", now).param("previous", policyId).update();
        audit.record("APPROVAL_POLICY_UPDATED", "EXECUTION_POLICY", id.toString(), java.util.Map.of(
            "previousPolicyId", policyId, "previousPolicyVersion", previous.policyVersion(),
            "policyVersion", version, "approvalMode", approvalMode.name()));
        return requireById(id);
    }

    public ExecutionPolicy requireById(UUID policyId) {
        return jdbc.sql("SELECT * FROM execution_policy WHERE id = :id")
            .param("id", policyId)
            .query((rs, rowNum) -> map(rs))
            .optional()
            .orElseThrow(() -> new NotFoundException("Execution policy not found: " + policyId));
    }

    /**
     * Canonical Phase 7 precedence:
     * exact mode+strategy+style -> mode+strategy+ANY -> mode default style -> mode default ANY.
     */
    public Optional<ExecutionPolicy> resolvePolicy(ExecutionMode mode, String strategy,
                                                    ExecutionPolicyTradingStyle style) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return jdbc.sql("""
                SELECT *
                  FROM execution_policy
                 WHERE active = TRUE
                   AND execution_mode = :mode
                   AND effective_from <= :now
                   AND (effective_to IS NULL OR effective_to > :now)
                   AND ((:strategy IS NOT NULL AND strategy = :strategy) OR strategy IS NULL)
                   AND (trading_style = :style OR trading_style = 'ANY')
                 ORDER BY CASE
                    WHEN :strategy IS NOT NULL AND strategy = :strategy AND trading_style = :style THEN 1
                    WHEN :strategy IS NOT NULL AND strategy = :strategy AND trading_style = 'ANY' THEN 2
                    WHEN strategy IS NULL AND trading_style = :style THEN 3
                    WHEN strategy IS NULL AND trading_style = 'ANY' THEN 4
                    ELSE 5
                 END,
                 policy_version DESC
                 LIMIT 1
                 FOR SHARE
                """)
            .param("mode", mode.name())
            .param("strategy", strategy, Types.VARCHAR)
            .param("style", style.name())
            .param("now", now)
            .query((rs, rowNum) -> map(rs))
            .optional();
    }

    public ExecutionPolicy requirePolicy(ExecutionMode mode, String strategy,
                                         ExecutionPolicyTradingStyle style) {
        return resolvePolicy(mode, strategy, style)
            .orElseThrow(() -> new IllegalStateException(
                "No active execution policy for mode=" + mode + ", strategy=" + strategy + ", style=" + style));
    }

    private ExecutionPolicy map(ResultSet rs) throws SQLException {
        return new ExecutionPolicy(
            rs.getObject("id", java.util.UUID.class),
            rs.getInt("policy_version"),
            ExecutionMode.valueOf(rs.getString("execution_mode")),
            rs.getString("strategy"),
            ExecutionPolicyTradingStyle.valueOf(rs.getString("trading_style")),
            ApprovalMode.valueOf(rs.getString("approval_mode")),
            rs.getBigDecimal("max_order_notional"),
            rs.getInt("max_daily_trades"),
            rs.getBigDecimal("max_gross_exposure"),
            rs.getBigDecimal("max_daily_loss"),
            strings(rs.getString("symbol_allowlist")),
            strings(rs.getString("symbol_denylist")),
            rs.getObject("session_start", java.time.LocalTime.class),
            rs.getObject("session_end", java.time.LocalTime.class),
            rs.getObject("effective_from", OffsetDateTime.class),
            rs.getObject("effective_to", OffsetDateTime.class), rs.getBoolean("active"));
    }

    private List<String> strings(String raw) {
        try {
            return json.readValue(raw, STRING_LIST).stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(String::toUpperCase)
                .distinct()
                .toList();
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid execution policy symbol list", ex);
        }
    }
}
