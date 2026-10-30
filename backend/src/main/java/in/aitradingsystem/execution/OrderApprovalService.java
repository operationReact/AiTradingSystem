package in.aitradingsystem.execution;

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
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderApprovalService {
    private final JdbcClient jdbc;
    private final Clock clock;

    public OrderApprovalService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
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
        jdbc.sql("""
                UPDATE order_approval
                   SET state = :state,
                       decision_reason = :reason,
                       decided_at = :now,
                       decided_by = :actor,
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

        // Winner and losers both read the same authoritative persisted state. This is what makes duplicate UI clicks
        // idempotent at the approval boundary instead of creating duplicate execution authority.
        return require(approvalId);
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
