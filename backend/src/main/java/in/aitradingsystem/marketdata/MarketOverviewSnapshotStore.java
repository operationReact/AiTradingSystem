package in.aitradingsystem.marketdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists the last verified Markets overview so it can be served when the app feed is down. */
@Repository
public class MarketOverviewSnapshotStore {
    private static final String UNIVERSE = MarketOverviewService.UNIVERSE_CODE;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MarketOverviewSnapshotStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void save(MarketOverviewService.Overview overview) {
        try {
            jdbc.update("""
                    INSERT INTO market_overview_snapshot (universe_code, generated_at, payload)
                    VALUES (?, ?, CAST(? AS jsonb))
                    ON CONFLICT (universe_code) DO UPDATE SET
                        generated_at = EXCLUDED.generated_at, payload = EXCLUDED.payload
                    """, UNIVERSE, Timestamp.from(overview.generatedAt()),
                    mapper.writeValueAsString(overview));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Market overview snapshot could not be serialized", ex);
        }
    }

    public Optional<MarketOverviewService.Overview> load() {
        List<String> payloads = jdbc.query(
                "SELECT payload::text FROM market_overview_snapshot WHERE universe_code = ?",
                (rs, row) -> rs.getString(1), UNIVERSE);
        if (payloads.isEmpty()) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(payloads.get(0), MarketOverviewService.Overview.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored market overview snapshot is unreadable", ex);
        }
    }
}
