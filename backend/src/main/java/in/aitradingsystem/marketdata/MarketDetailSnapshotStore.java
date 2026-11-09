package in.aitradingsystem.marketdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.marketdata.CustomerMarketDataController.DetailQuote;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists the last verified day-detail quote per instrument for stock detail when the app feed is down. */
@Repository
public class MarketDetailSnapshotStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MarketDetailSnapshotStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void save(DetailQuote detail) {
        try {
            jdbc.update("""
                    INSERT INTO market_detail_snapshot (instrument_id, fetched_at, payload)
                    VALUES (?, ?, CAST(? AS jsonb))
                    ON CONFLICT (instrument_id) DO UPDATE SET
                        fetched_at = EXCLUDED.fetched_at, payload = EXCLUDED.payload
                    """, detail.instrumentId(), Timestamp.from(detail.fetchedAt()),
                    mapper.writeValueAsString(detail));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Market detail snapshot could not be serialized", ex);
        }
    }

    public Optional<DetailQuote> load(long instrumentId) {
        List<String> payloads = jdbc.query(
                "SELECT payload::text FROM market_detail_snapshot WHERE instrument_id = ?",
                (rs, row) -> rs.getString(1), instrumentId);
        if (payloads.isEmpty()) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(payloads.get(0), DetailQuote.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored market detail snapshot is unreadable", ex);
        }
    }
}
