package in.aitradingsystem.marketdata;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MarketDataReconciliationRepository {
    private final JdbcTemplate jdbc;

    public MarketDataReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryClaim(UUID ownerId, Duration lease) {
        int updated = jdbc.update("""
                UPDATE market_data_reconciliation_claim
                   SET owner_id = ?, claimed_until = ?, updated_at = now()
                 WHERE singleton_id = 1
                   AND (claimed_until IS NULL OR claimed_until < now() OR owner_id = ?)
                """, ownerId, OffsetDateTime.now(ZoneOffset.UTC).plus(lease), ownerId);
        return updated == 1;
    }

    public void release(UUID ownerId) {
        jdbc.update("""
                UPDATE market_data_reconciliation_claim
                   SET owner_id = NULL, claimed_until = NULL, updated_at = now()
                 WHERE singleton_id = 1 AND owner_id = ?
                """, ownerId);
    }

    public List<Long> activeCashInstrumentIds(int limit) {
        return jdbc.query("""
                SELECT im.instrument_id
                  FROM instrument_master im
                  JOIN instrument_catalogue_state state ON state.active_version = im.catalogue_version
                 WHERE im.active = TRUE
                   AND im.exchange IN ('NSE','BSE')
                   AND im.instrument_type = 'EQ'
                 ORDER BY im.instrument_id
                 LIMIT ?
                """, (rs, row) -> rs.getLong(1), limit);
    }
}
