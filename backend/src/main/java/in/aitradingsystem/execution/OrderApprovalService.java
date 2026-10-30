package in.aitradingsystem.execution;

import in.aitradingsystem.audit.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.paper.OrderCommand;
import in.aitradingsystem.risk.RiskContext;
import in.aitradingsystem.risk.RiskDecisionV2;
import in.aitradingsystem.common.NotFoundException;
import in.aitradingsystem.common.PageResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderApprovalService {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final AuditService audit;

    public OrderApprovalService(JdbcClient jdbc, Clock clock, AuditService audit) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
    }

    /** Called in the serialized order transaction, after the immutable hard-risk decision has been persisted. */
    @Transactional
    public OrderApprovalView request(UUID orderId, OrderCommand command, RiskContext context,
                                     RiskDecisionV2 risk, ExecutionPolicy policy, ApprovalPolicyResult result) {
        if (result.decision() == ApprovalEvaluationDecision.REJECT) {
            throw new IllegalArgumentException("Rejected execution policy cannot create approval authority");
        }
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        boolean auto = result.decision() == ApprovalEvaluationDecision.AUTO_APPROVED;
        var snapshot = new java.util.LinkedHashMap<String, Object>();
        snapshot.put("symbol", command.symbol());
        snapshot.put("side", command.side());
        snapshot.put("quantity", command.quantity());
        snapshot.put("productType", command.productType());
        snapshot.put("orderType", "MARKET");
        snapshot.put("referencePrice", command.entryPrice());
        snapshot.put("stopLoss", command.stopLoss());
        snapshot.put("target", command.target());
        snapshot.put("strategyId", context.strategy());
        snapshot.put("exchange", context.exchange());
        snapshot.put("notional", risk.grossNotional());
        snapshot.put("approvalMode", policy.approvalMode());
        snapshot.put("policyReasons", result.reasonCodes());
        // Snapshot values are JSON primitives/enums; instants live in typed timestamp columns.
        String raw;
        try { raw = new ObjectMapper().writeValueAsString(snapshot); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
        jdbc.sql("""
            INSERT INTO order_approval (id, order_id, policy_id, policy_version, state, decision_reason,
                requested_at, expires_at, decided_at, decided_by, row_version, risk_decision_id, request_snapshot)
            VALUES (:id, :orderId, :policyId, :version, :state, :reason, :now, :expiry,
                :decidedAt, :actor, 0, :riskId, CAST(:snapshot AS jsonb))
            """)
            .param("id", id).param("orderId", orderId).param("policyId", policy.id())
            .param("version", policy.policyVersion()).param("state", auto ? "AUTO_APPROVED" : "PENDING")
            .param("reason", String.join(",", result.reasonCodes()))
            .param("now", now.atOffset(ZoneOffset.UTC)).param("expiry", now.plusSeconds(300).atOffset(ZoneOffset.UTC))
            .param("decidedAt", auto ? now.atOffset(ZoneOffset.UTC) : null, Types.TIMESTAMP_WITH_TIMEZONE)
            .param("actor", auto ? "SYSTEM" : null, Types.VARCHAR)
            .param("riskId", risk.decisionId()).param("snapshot", raw).update();
        jdbc.sql("UPDATE paper_order SET approval_id = :approvalId WHERE id = :orderId AND approval_id IS NULL")
            .param("approvalId", id).param("orderId", orderId).update();
        audit.record(auto ? "AUTO_APPROVAL_GRANTED" : "APPROVAL_REQUESTED", "ORDER_APPROVAL", id.toString(),
            Map.of("orderId", orderId.toString(), "policyId", policy.id(), "policyVersion", policy.policyVersion(),
                "riskDecisionId", risk.decisionId(), "reasons", result.reasonCodes()));
        return require(id);
    }

    public PageResponse<OrderApprovalView> list(OrderApprovalState state, int page, int size) {
        if (page < 0) throw new IllegalArgumentException("page must be non-negative");
        if (size < 1 || size > 100) throw new IllegalArgumentException("size must be between 1 and 100");

        String stateName = state == null ? null : state.name();
        long total = jdbc.sql("SELECT count(*) FROM order_approval WHERE (:state IS NULL OR state = :state)")
            .param("state", stateName, Types.VARCHAR)
            .query(Long.class)
            .single();
        List<OrderApprovalView> items = jdbc.sql("""
                SELECT *
                  FROM order_approval
                 WHERE (:state IS NULL OR state = :state)
                 ORDER BY requested_at DESC, id DESC
                 LIMIT :limit OFFSET :offset
                """)
            .param("state", stateName, Types.VARCHAR)
            .param("limit", size)
            .param("offset", page * size)
            .query((rs, row) -> map(rs))
            .list();
        return PageResponse.of(items, page, size, total);
    }

    public OrderApprovalView require(UUID approvalId) {
        return jdbc.sql("SELECT * FROM order_approval WHERE id = :id")
            .param("id", approvalId)
            .query((rs, row) -> map(rs))
            .optional()
            .orElseThrow(() -> new NotFoundException("Order approval not found: " + approvalId));
    }

    /**
     * Compare-and-set approval transition. Exactly one concurrent PENDING decision with the same expected row version
     * can mutate the row. A losing/duplicate click returns the current persisted state without a second transition.
     */
    @Transactional
    public OrderApprovalView decide(UUID approvalId, int expectedRowVersion, OrderApprovalState decision,
                                    String actor, String reason) {
        if (approvalId == null) throw new IllegalArgumentException("approvalId is required");
        if (expectedRowVersion < 0) throw new IllegalArgumentException("expectedRowVersion must be non-negative");
        if (decision == null || !decision.operatorDecision()) {
            throw new IllegalArgumentException("decision must be APPROVED, REJECTED or CANCELLED");
        }
        if (actor == null || actor.isBlank() || actor.length() > 120) {
            throw new IllegalArgumentException("actor must be 1-120 characters");
        }
        if (reason != null && reason.length() > 300) {
            throw new IllegalArgumentException("reason must be at most 300 characters");
        }

        Instant now = clock.instant();
        int updated = jdbc.sql("""
                UPDATE order_approval
                   SET state = CASE WHEN expires_at <= :now THEN 'EXPIRED' ELSE :state END,
                       decision_reason = CASE WHEN expires_at <= :now THEN 'APPROVAL_EXPIRED' ELSE :reason END,
                       decided_at = :now,
                       decided_by = CASE WHEN expires_at <= :now THEN 'SYSTEM' ELSE :actor END,
                       row_version = row_version + 1
                 WHERE id = :id
                   AND state = 'PENDING'
                   AND row_version = :expected
                """)
            .param("state", decision.name())
            .param("reason", reason, Types.VARCHAR)
            .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("actor", actor.trim())
            .param("id", approvalId)
            .param("expected", expectedRowVersion)
            .update();

        OrderApprovalView result = require(approvalId);
        // Only the transaction that changed the row owns the audit event. Comparing before/after snapshots in a
        // controller cannot distinguish two concurrent identical decisions and can emit the same event twice.
        if (updated == 1) {
            audit.record("APPROVAL_" + result.state().name(), "ORDER_APPROVAL", approvalId.toString(), Map.of(
                "orderId", result.orderId().toString(),
                "policyVersion", result.policyVersion(),
                "rowVersion", result.rowVersion(),
                "actor", result.decidedBy()));
        }
        return result;
    }

    private static OrderApprovalView map(ResultSet rs) throws SQLException {
        OffsetDateTime decidedAt = rs.getObject("decided_at", OffsetDateTime.class);
        return new OrderApprovalView(
            rs.getObject("id", UUID.class),
            rs.getObject("order_id", UUID.class),
            rs.getObject("policy_id", UUID.class),
            rs.getInt("policy_version"),
            OrderApprovalState.valueOf(rs.getString("state")),
            rs.getString("decision_reason"),
            rs.getObject("requested_at", OffsetDateTime.class).toInstant(),
            rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
            decidedAt == null ? null : decidedAt.toInstant(),
            rs.getString("decided_by"),
            rs.getInt("row_version"),
            rs.getObject("risk_decision_id", UUID.class),
            rs.getString("request_snapshot"));
    }
}
