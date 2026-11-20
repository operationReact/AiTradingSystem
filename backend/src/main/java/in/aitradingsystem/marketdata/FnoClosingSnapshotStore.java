package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Persists source-labelled F&O closing snapshots (table fno_closing_snapshot, V99).
 *
 * <p>Persistence is OFF by default and requires BOTH the operator flag
 * {@code minttick.fno-research.persist-enabled=true} AND a server-side rights
 * verification passed by the caller. Retention purge always runs regardless
 * of the persist flag, so data written earlier is still removed on schedule.
 */
@Component
public class FnoClosingSnapshotStore {
    static final String RIGHTS_BASIS = "INTERNAL_RESEARCH_VERIFIED";
    static final int MAX_RETENTION_DAYS = 365;

    private static final String INSERT_SQL = """
        INSERT INTO fno_closing_snapshot (provider_label, instrument_key, underlying_key,
            session_date, exchange_at, observed_at, bid, ask, open_interest_units,
            session_volume_units, rights_basis, retain_until)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (provider_label, instrument_key, session_date) DO NOTHING
        """;
    private static final String PURGE_SQL = "DELETE FROM fno_closing_snapshot WHERE retain_until < ?";

    public enum Outcome { STORED, DUPLICATE_IGNORED, PERSISTENCE_DISABLED }

    /** One closing observation. Prices are INR per underlying unit. */
    public record Snapshot(String providerLabel, String instrumentKey, String underlyingKey,
                           LocalDate sessionDate, Instant exchangeAt, Instant observedAt,
                           BigDecimal bid, BigDecimal ask, Long openInterestUnits,
                           Long sessionVolumeUnits, LocalDate retainUntil) {}

    private final JdbcTemplate jdbc;
    private final boolean persistEnabled;

    public FnoClosingSnapshotStore(JdbcTemplate jdbc,
            @Value("${minttick.fno-research.persist-enabled:false}") boolean persistEnabled) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.persistEnabled = persistEnabled;
    }

    /**
     * @param rightsVerifiedByServerGate true only when the server-side rights gate has
     *        verified internal research use for this provider and instrument.
     */
    public Outcome save(Snapshot s, boolean rightsVerifiedByServerGate) {
        if (!persistEnabled) return Outcome.PERSISTENCE_DISABLED;
        if (!rightsVerifiedByServerGate) {
            throw new IllegalStateException("FNO_SNAPSHOT_RIGHTS_NOT_VERIFIED");
        }
        validate(s);
        int rows = jdbc.update(INSERT_SQL, s.providerLabel(), s.instrumentKey(), s.underlyingKey(),
            s.sessionDate(), s.exchangeAt(), s.observedAt(), s.bid(), s.ask(),
            s.openInterestUnits(), s.sessionVolumeUnits(), RIGHTS_BASIS, s.retainUntil());
        return rows == 1 ? Outcome.STORED : Outcome.DUPLICATE_IGNORED;
    }

    /** Deletes rows whose retention window ended before {@code today}. Always enabled. */
    public int purgeExpired(LocalDate today) {
        return jdbc.update(PURGE_SQL, today);
    }

    static void validate(Snapshot s) {
        if (s == null) throw new IllegalArgumentException("FNO_SNAPSHOT_MISSING");
        if (FnoMarketDataProvider.fromWire(s.providerLabel()).isEmpty()) {
            throw new IllegalArgumentException("FNO_SNAPSHOT_PROVIDER_NOT_CANONICAL");
        }
        if (s.instrumentKey() == null || !s.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("FNO_SNAPSHOT_INSTRUMENT_INVALID");
        }
        if (s.bid() == null || s.ask() == null || s.bid().signum() <= 0 || s.ask().compareTo(s.bid()) < 0) {
            throw new IllegalArgumentException("FNO_SNAPSHOT_PRICE_INVALID");
        }
        if (s.exchangeAt() == null || s.observedAt() == null || s.observedAt().isBefore(s.exchangeAt())) {
            throw new IllegalArgumentException("FNO_SNAPSHOT_TIME_INVALID");
        }
        if (s.sessionDate() == null || s.retainUntil() == null
                || s.retainUntil().isBefore(s.sessionDate())
                || s.retainUntil().isAfter(s.sessionDate().plusDays(MAX_RETENTION_DAYS))) {
            throw new IllegalArgumentException("FNO_SNAPSHOT_RETENTION_INVALID");
        }
        if ((s.openInterestUnits() != null && s.openInterestUnits() < 0)
                || (s.sessionVolumeUnits() != null && s.sessionVolumeUnits() < 0)) {
            throw new IllegalArgumentException("FNO_SNAPSHOT_UNITS_INVALID");
        }
    }
}
