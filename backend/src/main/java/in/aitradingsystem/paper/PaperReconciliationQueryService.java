package in.aitradingsystem.paper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PaperReconciliationQueryService {
    private final JdbcClient jdbc;

    public PaperReconciliationQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReconciliationRunView> recentRuns(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return jdbc.sql("""
                SELECT id, status, started_at, completed_at, checks_performed, difference_count,
                       error_code, error_message
                  FROM paper_reconciliation_run
                 ORDER BY started_at DESC, id
                 LIMIT :limit
                """)
            .param("limit", limit)
            .query((rs, row) -> new ReconciliationRunView(
                rs.getObject("id", UUID.class), rs.getString("status"),
                instant(rs.getObject("started_at", OffsetDateTime.class)),
                instant(rs.getObject("completed_at", OffsetDateTime.class)),
                rs.getInt("checks_performed"), rs.getInt("difference_count"),
                rs.getString("error_code"), rs.getString("error_message")))
            .list();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record ReconciliationRunView(UUID id, String status, Instant startedAt, Instant completedAt,
                                        int checksPerformed, int differenceCount,
                                        String errorCode, String errorMessage) {}
}
