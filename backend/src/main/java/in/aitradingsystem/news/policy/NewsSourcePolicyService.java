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
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;
import in.aitradingsystem.audit.AuditService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Reads the stored rights policy and applies the default-deny decision. Read-only. */
@Service
public class NewsSourcePolicyService {
    private final JdbcClient jdbc;
    private final AuditService audit;

    public NewsSourcePolicyService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
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

    /** Updates one pre-seeded, supported publisher policy and records the admin decision atomically. */
    @Transactional
    public Row update(Row row) {
        String uses = row.permittedUses().stream().map(Enum::name).sorted()
            .collect(Collectors.joining(","));
        int changed = jdbc.sql("""
                UPDATE news_source_policy
                   SET license_state = :state,
                       permitted_uses = CAST(:uses AS text[]),
                       reviewer_ref = :reviewerRef,
                       terms_ref = :termsRef,
                       effective_from = :effectiveFrom,
                       effective_to = :effectiveTo,
                       content_ttl_days = :ttl,
                       updated_at = now()
                 WHERE source_id = :sourceId
                """)
            .param("state", row.state().name())
            .param("uses", row.permittedUses().isEmpty() ? "{}" : "{" + uses + "}")
            .param("reviewerRef", row.reviewerRef())
            .param("termsRef", row.termsRef())
            .param("effectiveFrom", row.effectiveFrom().orElse(null))
            .param("effectiveTo", row.effectiveTo().orElse(null))
            .param("ttl", row.contentTtlDays())
            .param("sourceId", row.sourceId())
            .update();
        if (changed != 1) throw new IllegalArgumentException("Unsupported news source");

        // Keep stable evidence references in the policy row, but only their presence in the audit trail.
        audit.record("NEWS_SOURCE_POLICY_UPDATED", "NEWS_SOURCE", row.sourceId(), Map.of(
            "licenseState", row.state().name(),
            "permittedUses", row.permittedUses().stream().map(Enum::name).sorted().toList(),
            "reviewerReferencePresent", row.reviewerRef() != null,
            "termsReferencePresent", row.termsRef() != null,
            "effectiveFrom", row.effectiveFrom().map(Instant::toString).orElse(""),
            "effectiveTo", row.effectiveTo().map(Instant::toString).orElse(""),
            "contentTtlDays", row.contentTtlDays()));
        return find(row.sourceId()).orElseThrow(() -> new IllegalStateException("Updated source policy unavailable"));
    }
}
