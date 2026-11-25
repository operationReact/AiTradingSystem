package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
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

    private static final String PAIR_ROWS_SQL = """
        SELECT provider_label, instrument_key, session_date, exchange_at, observed_at,
               bid, ask, open_interest_units, session_volume_units
        FROM fno_closing_snapshot
        WHERE instrument_key IN (?, ?) AND session_date BETWEEN ? AND ?
        ORDER BY session_date, instrument_key
        """;
    /** STR-173: exact retained research-attested closing rows, not a full source-rights proof. */
    private static final String VERIFIED_OI_CONTRACT_ROWS_SQL = """
        SELECT provider_label, instrument_key, session_date, exchange_at, observed_at,
               bid, ask, open_interest_units, session_volume_units
        FROM fno_closing_snapshot
        WHERE instrument_key = ? AND session_date BETWEEN ? AND ?
          AND rights_basis = ? AND retain_until >= ?
        ORDER BY session_date, provider_label
        """;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public enum Outcome { STORED, DUPLICATE_IGNORED, PERSISTENCE_DISABLED }

    /**
     * One closing observation. Prices are INR per underlying unit. closeVerified must
     * be true: only verified closing snapshots may be stored.
     */
    public record Snapshot(String providerLabel, String instrumentKey, String underlyingKey,
                           LocalDate sessionDate, Instant exchangeAt, Instant observedAt,
                           BigDecimal bid, BigDecimal ask, Long openInterestUnits,
                           Long sessionVolumeUnits, LocalDate retainUntil, boolean closeVerified) {}

    /** A stored row as read back. Nullable OI or volume is kept as null, never zero. */
    public record StoredRow(String providerLabel, String instrumentKey, LocalDate sessionDate,
                            Instant exchangeAt, Instant observedAt, BigDecimal bid, BigDecimal ask,
                            Long openInterestUnits, Long sessionVolumeUnits) {}

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
            Date.valueOf(s.sessionDate()), Timestamp.from(s.exchangeAt()), Timestamp.from(s.observedAt()),
            s.bid(), s.ask(), s.openInterestUnits(), s.sessionVolumeUnits(), RIGHTS_BASIS,
            Date.valueOf(s.retainUntil()));
        return rows == 1 ? Outcome.STORED : Outcome.DUPLICATE_IGNORED;
    }

    /** Rows for one near/far instrument pair in an inclusive session-date window, oldest first. */
    public List<StoredRow> pairRows(String nearKey, String farKey, LocalDate from, LocalDate to) {
        return jdbc.query(PAIR_ROWS_SQL, (rs, n) -> new StoredRow(
            rs.getString("provider_label"), rs.getString("instrument_key"),
            rs.getObject("session_date", LocalDate.class),
            rs.getTimestamp("exchange_at").toInstant(), rs.getTimestamp("observed_at").toInstant(),
            rs.getBigDecimal("bid"), rs.getBigDecimal("ask"),
            (Long) rs.getObject("open_interest_units"), (Long) rs.getObject("session_volume_units")),
            nearKey, farKey, Date.valueOf(from), Date.valueOf(to));
    }

    /**
     * Research-only exact contract OI reader. The stored rights-basis is necessary,
     * not sufficient proof of current source-use rights. Never writes or backfills.
     */
    public List<StoredRow> verifiedOiContractRows(String contractKey, LocalDate from,
                                                   LocalDate to) {
        if (contractKey == null || !contractKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || from == null || to == null || from.isAfter(to)
                || to.isAfter(LocalDate.now(IST))) {
            throw new IllegalArgumentException("FNO_OI_READ_SCOPE_INVALID");
        }
        return jdbc.query(VERIFIED_OI_CONTRACT_ROWS_SQL, (rs, n) -> new StoredRow(
            rs.getString("provider_label"), rs.getString("instrument_key"),
            rs.getObject("session_date", LocalDate.class),
            rs.getTimestamp("exchange_at").toInstant(), rs.getTimestamp("observed_at").toInstant(),
            rs.getBigDecimal("bid"), rs.getBigDecimal("ask"),
            (Long) rs.getObject("open_interest_units"), (Long) rs.getObject("session_volume_units")),
            contractKey, Date.valueOf(from), Date.valueOf(to), RIGHTS_BASIS,
            Date.valueOf(LocalDate.now(IST)));
    }

    /** Deletes rows whose retention window ended before {@code today}. Always enabled. */
    public int purgeExpired(LocalDate today) {
        return jdbc.update(PURGE_SQL, Date.valueOf(today));
    }

    /** Daily retention cleanup at 03:45 IST. Runs regardless of the persist flag. */
    @Scheduled(cron = "0 45 3 * * *", zone = "Asia/Kolkata")
    public void scheduledRetentionPurge() {
        purgeExpired(LocalDate.now(IST));
    }

    static void validate(Snapshot s) {
        if (s == null) throw new IllegalArgumentException("FNO_SNAPSHOT_MISSING");
        if (!s.closeVerified()) throw new IllegalArgumentException("FNO_SNAPSHOT_CLOSE_NOT_VERIFIED");
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
