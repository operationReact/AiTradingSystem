package in.aitradingsystem.news.policy;

import in.aitradingsystem.news.policy.NewsSourcePolicy.Decision;
import in.aitradingsystem.news.policy.NewsSourcePolicy.LicenseState;
import in.aitradingsystem.news.policy.NewsSourcePolicy.Row;
import in.aitradingsystem.news.policy.NewsSourcePolicy.Use;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Reads the stored rights policy and applies the default-deny decision. Read-only. */
@Service
public class NewsSourcePolicyService {
    private final JdbcClient jdbc;

    public NewsSourcePolicyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) return Optional.empty();
        return jdbc.sql("""
                SELECT source_id, publisher, license_state, permitted_uses, reviewer_ref, terms_ref,
                       effective_from, effective_to, content_ttl_days
                  FROM news_source_policy
                 WHERE source_id = :id
                """)
            .param("id", sourceId.trim())
            .query((rs, n) -> {
                List<Use> uses = new ArrayList<>();
                Object[] raw = (Object[]) rs.getArray("permitted_uses").getArray();
                for (Object o : raw) uses.add(Use.valueOf(String.valueOf(o)));
                Timestamp from = rs.getTimestamp("effective_from");
                Timestamp to = rs.getTimestamp("effective_to");
                return new Row(rs.getString("source_id"), rs.getString("publisher"),
                    LicenseState.valueOf(rs.getString("license_state")), uses,
                    rs.getString("reviewer_ref"), rs.getString("terms_ref"),
                    Optional.ofNullable(from).map(Timestamp::toInstant),
                    Optional.ofNullable(to).map(Timestamp::toInstant),
                    rs.getInt("content_ttl_days"));
            })
            .optional();
    }

    public Decision decide(String sourceId, Use use, Instant at) {
        return NewsSourcePolicy.evaluate(find(sourceId), use, at);
    }

    public boolean permitted(String sourceId, Use use, Instant at) {
        return decide(sourceId, use, at).permitted();
    }
}
