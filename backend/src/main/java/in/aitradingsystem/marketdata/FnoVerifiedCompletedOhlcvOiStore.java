package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Shared read-only exchange-session OHLCV/OI evidence boundary for futures
 * research. It does NOT promote ordinary market_candle historical fetch time,
 * quote snapshots or downloaded data into original publication timestamps.
 * Data and model-inference rights must be independently attested.
 */
@Component
public class FnoVerifiedCompletedOhlcvOiStore {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String SQL = """
        SELECT s.provider_code, s.instrument_key, s.underlying_key,
               s.listed_from, s.expiry, s.lot_size, s.session_date,
               s.open_price, s.high_price, s.low_price, s.close_price,
               s.traded_volume_units, s.final_open_interest_units,
               s.ohlcv_first_available_at, s.oi_first_available_at,
               cal.session_close_at, s.ohlcv_source_digest,
               s.oi_source_digest, s.listing_source_digest
        FROM fno_verified_completed_ohlcv_oi_session s
        JOIN golden_data_provider p ON p.provider_code = s.provider_code
            AND p.source_kind = 'EXTERNAL'
        JOIN golden_source_rights_receipt ohlcv_rights
            ON ohlcv_rights.receipt_id = s.ohlcv_rights_receipt_id
            AND ohlcv_rights.provider_id = p.provider_id
            AND ohlcv_rights.source_domain = 'nse_fo_completed_ohlcv'
            AND ohlcv_rights.permitted_use = 'MODEL_INFERENCE'
            AND ohlcv_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt oi_rights
            ON oi_rights.receipt_id = s.oi_rights_receipt_id
            AND oi_rights.provider_id = p.provider_id
            AND oi_rights.source_domain = 'nse_fo_final_oi'
            AND oi_rights.permitted_use = 'MODEL_INFERENCE'
            AND oi_rights.receipt_status = 'VERIFIED'
        JOIN golden_historical_exchange_session_evidence cal
            ON cal.evidence_id = s.calendar_evidence_id
            AND cal.provider_id = p.provider_id
            AND cal.source_domain = 'nse_fo_trading_calendar'
            AND cal.exchange = 'NSE'
            AND cal.session_date = s.session_date
            AND cal.session_status = 'TRADING'
        WHERE s.instrument_key = ?
          AND s.session_date BETWEEN ? AND ?
          AND s.retain_until >= ?
          AND s.captured_at <= ?
          AND s.ohlcv_first_available_at >= cal.session_close_at
          AND s.oi_first_available_at >= cal.session_close_at
          AND s.ohlcv_first_available_at <= ?
          AND s.oi_first_available_at <= ?
          AND cal.first_available_at <= ?
          AND cal.verified_at <= ? AND cal.captured_at <= ?
          AND ohlcv_rights.valid_from <= ?
          AND (ohlcv_rights.valid_to IS NULL OR ohlcv_rights.valid_to > ?)
          AND ohlcv_rights.verified_at <= ? AND ohlcv_rights.captured_at <= ?
          AND oi_rights.valid_from <= ?
          AND (oi_rights.valid_to IS NULL OR oi_rights.valid_to > ?)
          AND oi_rights.verified_at <= ? AND oi_rights.captured_at <= ?
        ORDER BY s.session_date, s.provider_code
        """;

    /** Aggregate original-source eligibility, never a strategy certification. */
    private static final String ELIGIBILITY_SQL = """
        WITH verified_source AS (
          SELECT s.instrument_key, s.provider_code, s.session_date
          FROM fno_verified_completed_ohlcv_oi_session s
          JOIN golden_data_provider p ON p.provider_code = s.provider_code
              AND p.source_kind = 'EXTERNAL'
          JOIN golden_source_rights_receipt ohlcv_rights
              ON ohlcv_rights.receipt_id = s.ohlcv_rights_receipt_id
              AND ohlcv_rights.provider_id = p.provider_id
              AND ohlcv_rights.source_domain = 'nse_fo_completed_ohlcv'
              AND ohlcv_rights.permitted_use = 'MODEL_INFERENCE'
              AND ohlcv_rights.receipt_status = 'VERIFIED'
          JOIN golden_source_rights_receipt oi_rights
              ON oi_rights.receipt_id = s.oi_rights_receipt_id
              AND oi_rights.provider_id = p.provider_id
              AND oi_rights.source_domain = 'nse_fo_final_oi'
              AND oi_rights.permitted_use = 'MODEL_INFERENCE'
              AND oi_rights.receipt_status = 'VERIFIED'
          JOIN golden_historical_exchange_session_evidence cal
              ON cal.evidence_id = s.calendar_evidence_id
              AND cal.provider_id = p.provider_id
              AND cal.source_domain = 'nse_fo_trading_calendar'
              AND cal.exchange = 'NSE'
              AND cal.session_date = s.session_date
              AND cal.session_status = 'TRADING'
          WHERE s.session_date BETWEEN ? AND ?
            AND s.expiry BETWEEN ? AND ?
            AND s.retain_until >= ?
            AND s.captured_at <= ?
            AND s.ohlcv_first_available_at >= cal.session_close_at
            AND s.oi_first_available_at >= cal.session_close_at
            AND s.ohlcv_first_available_at <= ?
            AND s.oi_first_available_at <= ?
            AND cal.first_available_at <= ?
            AND cal.verified_at <= ?
            AND cal.captured_at <= ?
            AND ohlcv_rights.valid_from <= ?
            AND (ohlcv_rights.valid_to IS NULL OR ohlcv_rights.valid_to > ?)
            AND ohlcv_rights.verified_at <= ?
            AND ohlcv_rights.captured_at <= ?
            AND oi_rights.valid_from <= ?
            AND (oi_rights.valid_to IS NULL OR oi_rights.valid_to > ?)
            AND oi_rights.verified_at <= ?
            AND oi_rights.captured_at <= ?
        )
        SELECT
          (SELECT COUNT(*) FROM fno_verified_completed_ohlcv_oi_session) AS total_rows,
          (SELECT COUNT(*) FROM verified_source) AS eligible_rows,
          (SELECT COUNT(*) FROM (
              SELECT provider_code, instrument_key
              FROM verified_source
              GROUP BY provider_code, instrument_key
              HAVING COUNT(DISTINCT session_date) >= 7
          ) candidates) AS possible_seven_session_windows
        """;

    /** Only counts; neither eligible source rows nor seven dates prove STR-176 E2E. */
    public record HistoricalEligibility(
        long totalOriginalRows, long rightsJoinedAsOfRows,
        long possibleSevenSessionWindows) {}

    public HistoricalEligibility inspectHistoricalEligibility(Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        var today = asOf.atZone(IST).toLocalDate();
        var last = today.minusDays(1);
        var first = last.minusDays(25);
        Object[] bind = new Object[19];
        bind[0] = Date.valueOf(first);
        bind[1] = Date.valueOf(last);
        bind[2] = Date.valueOf(today.plusDays(7));
        bind[3] = Date.valueOf(today.plusDays(120));
        bind[4] = Date.valueOf(today);
        java.util.Arrays.fill(bind, 5, bind.length, Timestamp.from(asOf));
        return jdbc.queryForObject(ELIGIBILITY_SQL, (rs, index) ->
            new HistoricalEligibility(
                rs.getLong("total_rows"),
                rs.getLong("eligible_rows"),
                rs.getLong("possible_seven_session_windows")), bind);
    }

    public record VerifiedBar(
        String provider, String instrumentKey, String underlyingKey,
        LocalDate listedFrom, LocalDate expiry, int lotSize, LocalDate sessionDate,
        BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
        long tradedVolumeUnits, long openInterestUnits,
        Instant ohlcvFirstAvailableAt, Instant oiFirstAvailableAt,
        Instant verifiedSessionCloseAt) {}

    /** Only model-inference-rights/journal/calendar joined rows are visible. */
    public record ReplayProofBar(
        VerifiedBar bar, String originalOhlcvDigest,
        String originalOiDigest, String originalListingDigest) {}

    private final JdbcTemplate jdbc;

    public FnoVerifiedCompletedOhlcvOiStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public List<VerifiedBar> readExactContract(String instrumentKey, LocalDate from,
                                                LocalDate to, Instant asOf) {
        if (instrumentKey == null || !instrumentKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || from == null || to == null || asOf == null
                || from.isAfter(to) || from.isBefore(to.minusDays(30))
                || to.isAfter(asOf.atZone(IST).toLocalDate())) {
            throw new IllegalArgumentException("FNO_COMPLETED_OHLCV_OI_SCOPE_INVALID");
        }
        Timestamp cutoff = Timestamp.from(asOf);
        return jdbc.query(SQL, (rs, i) -> mapBar(rs),
            instrumentKey, Date.valueOf(from), Date.valueOf(to),
            Date.valueOf(asOf.atZone(IST).toLocalDate()),
            cutoff, cutoff, cutoff, cutoff, cutoff, cutoff, cutoff,
            cutoff, cutoff, cutoff, cutoff, cutoff, cutoff, cutoff);
    }

    /**
     * Read the same SQL-rights-attested exact futures source as STR-176,
     * including the original immutable source digests. No historical
     * publication time or contract quote is inferred from a daily label.
     */
    public List<ReplayProofBar> readExactReplayContract(
            String instrumentKey, LocalDate from, LocalDate to, Instant asOf) {
        if (instrumentKey == null || !instrumentKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || from == null || to == null || asOf == null
                || from.isAfter(to) || from.isBefore(to.minusDays(30))
                || to.isAfter(asOf.atZone(IST).toLocalDate())) {
            throw new IllegalArgumentException("FNO_COMPLETED_OHLCV_OI_SCOPE_INVALID");
        }
        Timestamp cutoff = Timestamp.from(asOf);
        return jdbc.query(SQL, (rs, i) -> new ReplayProofBar(
            mapBar(rs), rs.getString("ohlcv_source_digest"),
            rs.getString("oi_source_digest"),
            rs.getString("listing_source_digest")),
            instrumentKey, Date.valueOf(from), Date.valueOf(to),
            Date.valueOf(asOf.atZone(IST).toLocalDate()),
            cutoff, cutoff, cutoff, cutoff, cutoff, cutoff, cutoff,
            cutoff, cutoff, cutoff, cutoff, cutoff, cutoff, cutoff);
    }

    private static VerifiedBar mapBar(ResultSet rs) throws SQLException {
        return new VerifiedBar(
            rs.getString("provider_code"), rs.getString("instrument_key"),
            rs.getString("underlying_key"),
            rs.getObject("listed_from", LocalDate.class),
            rs.getObject("expiry", LocalDate.class),
            rs.getInt("lot_size"), rs.getObject("session_date", LocalDate.class),
            rs.getBigDecimal("open_price"), rs.getBigDecimal("high_price"),
            rs.getBigDecimal("low_price"), rs.getBigDecimal("close_price"),
            rs.getLong("traded_volume_units"), rs.getLong("final_open_interest_units"),
            rs.getTimestamp("ohlcv_first_available_at").toInstant(),
            rs.getTimestamp("oi_first_available_at").toInstant(),
            rs.getTimestamp("session_close_at").toInstant());
    }

    public int purgeExpired(LocalDate today) {
        return jdbc.update(
            "DELETE FROM fno_verified_completed_ohlcv_oi_session WHERE retain_until < ?",
            Date.valueOf(Objects.requireNonNull(today)));
    }

    @Scheduled(cron = "0 51 3 * * *", zone = "Asia/Kolkata")
    public void scheduledRetentionPurge() {
        purgeExpired(LocalDate.now(IST));
    }
}
