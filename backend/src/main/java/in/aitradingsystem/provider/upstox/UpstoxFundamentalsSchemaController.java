package in.aitradingsystem.provider.upstox;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Owner-only catalog capability read. Never queries source payloads or claims data coverage. */
@RestController
@RequestMapping("/api/customer/owner-upstox-analytics/v1")
@ConditionalOnProperty(prefix="minttick.upstox-analytics", name="fundamentals-schema-audit-enabled", havingValue="true")
public class UpstoxFundamentalsSchemaController {
    private final JdbcTemplate jdbc;
    private final String ownerSubject;
    private final Clock clock;

    public UpstoxFundamentalsSchemaController(JdbcTemplate jdbc,
            @Value("${MINTTICK_ANGEL_ONE_OWNER_SUB:}") String ownerSubject, Clock clock) {
        this.jdbc = jdbc;
        this.ownerSubject = ownerSubject;
        this.clock = clock;
    }

    public record Candidate(String tableName, long temporalColumns) {}
    public record Snapshot(Instant observedAt, String dataSource, String rightsState,
            String status, List<Candidate> candidates, Long fundamentalsEntities,
            Long fundamentalsRecords, Long corporateEvents) {}

    @GetMapping("/fundamentals-schema")
    public ResponseEntity<Snapshot> inspect(Authentication authentication) {
        if (ownerSubject.isBlank() || !(authentication instanceof JwtAuthenticationToken jwt)
                || !ownerSubject.equals(jwt.getToken().getSubject())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner only");
        }
        List<Candidate> candidates = jdbc.query("""
                SELECT table_name, count(*) FILTER (WHERE lower(column_name) IN
                    ('published_at','available_at','observed_at','committed_at'))
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND (lower(table_name) LIKE '%fundamental%'
                    OR lower(table_name) LIKE '%corporate%event%'
                    OR lower(table_name) LIKE '%corporate%action%')
                GROUP BY table_name ORDER BY table_name LIMIT 100
                """, (rs, row) -> new Candidate(rs.getString(1), rs.getLong(2)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new Snapshot(clock.instant(), "POSTGRESQL_CATALOG_ONLY", "UNKNOWN",
                    candidates.isEmpty() ? "NO_VISIBLE_CANDIDATES" : "SCHEMA_REVIEW_REQUIRED",
                    candidates, null, null, null));
    }
}
