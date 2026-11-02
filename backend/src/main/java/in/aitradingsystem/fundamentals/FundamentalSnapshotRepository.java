package in.aitradingsystem.fundamentals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FundamentalSnapshotRepository {
    private static final TypeReference<Map<String, Double>> METRICS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public FundamentalSnapshotRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<String> insert(
            String symbol,
            String provider,
            String providerRecordId,
            Instant periodEnd,
            Instant availableAt,
            Map<String, Double> metrics,
            String contentHash) {
        String metricsJson = toJson(metrics);
        List<String> ids = jdbc.query("""
            INSERT INTO fundamental_snapshot
                (symbol, provider, provider_record_id, period_end, available_at, metrics, content_hash)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (provider, provider_record_id) DO NOTHING
            RETURNING id::text
            """, (rs, row) -> rs.getString(1),
            symbol, provider, providerRecordId, Timestamp.from(periodEnd), Timestamp.from(availableAt),
            metricsJson, contentHash);
        return ids.stream().findFirst();
    }

    public List<FundamentalSnapshotRecord> researchSnapshots(
            Instant fromInclusive, Instant toExclusive, int limit) {
        return jdbc.query("""
            SELECT id::text, symbol, provider, provider_record_id, period_end, available_at,
                   metrics::text AS metrics_json, content_hash, ingested_at
            FROM fundamental_snapshot
            WHERE available_at >= ?
              AND available_at < ?
            ORDER BY available_at, id
            LIMIT ?
            """, (rs, row) -> new FundamentalSnapshotRecord(
                rs.getString("id"),
                rs.getString("symbol"),
                rs.getString("provider"),
                rs.getString("provider_record_id"),
                rs.getTimestamp("period_end").toInstant(),
                rs.getTimestamp("available_at").toInstant(),
                fromJson(rs.getString("metrics_json")),
                rs.getString("content_hash"),
                rs.getTimestamp("ingested_at").toInstant()),
            Timestamp.from(fromInclusive), Timestamp.from(toExclusive), limit);
    }

    private String toJson(Map<String, Double> metrics) {
        try {
            return objectMapper.writeValueAsString(metrics);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("fundamental metrics must be JSON serializable", exception);
        }
    }

    private Map<String, Double> fromJson(String json) {
        try {
            return Map.copyOf(objectMapper.readValue(json, METRICS_TYPE));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored fundamental metrics are invalid JSON", exception);
        }
    }
}
