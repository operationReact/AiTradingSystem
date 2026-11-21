package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.sql.Date;
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
 * Read-only STR-174 source boundary. Distinct authenticated final futures CLOSE
 * and finalized OI first-availability evidence MUST exist. Bid/ask, LTP,
 * synthetic close, later backfill acquisition time and raw OHLC candles are
 * deliberately not accepted as settlement evidence.
 *
 * No insert, update, download, broker call, order call, or permission mutation.
 */
@Component
public class FnoVerifiedFinalPriceOiSessionStore {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Only pre-existing, provider-scoped, active VERIFIED research receipts are eligible. */
    private static final String SELECT_SQL = """
        SELECT s.provider_label, s.instrument_key, s.underlying_key, s.expiry_date,
               s.lot_size, s.session_date, s.final_close_price,
               s.final_price_first_available_at, s.final_open_interest_units,
               s.traded_volume_units, s.finalized_oi_first_available_at,
               cal.session_close_at
        FROM fno_verified_final_price_oi_session s
        JOIN golden_data_provider p ON p.provider_code = s.provider_label
            AND p.source_kind = 'EXTERNAL'
        JOIN golden_source_rights_receipt price_rights
            ON price_rights.receipt_id = s.price_rights_receipt_id
            AND price_rights.provider_id = p.provider_id
            AND price_rights.source_domain = 'nse_fo_final_price'
            AND price_rights.permitted_use = 'MODEL_INFERENCE'
            AND price_rights.receipt_status = 'VERIFIED'
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
          AND s.recorded_at <= ?
          AND s.final_price_first_available_at <= ?
          AND s.finalized_oi_first_available_at <= ?
          AND cal.verified_at <= ? AND cal.captured_at <= ?
          AND cal.first_available_at <= ?
          AND price_rights.valid_from <= ?
          AND (price_rights.valid_to IS NULL OR price_rights.valid_to > ?)
          AND price_rights.verified_at <= ? AND price_rights.captured_at <= ?
          AND oi_rights.valid_from <= ?
          AND (oi_rights.valid_to IS NULL OR oi_rights.valid_to > ?)
          AND oi_rights.verified_at <= ? AND oi_rights.captured_at <= ?
        ORDER BY s.session_date, s.provider_label
        """;

    public record FinalSession(String providerLabel, String instrumentKey,
                               String underlyingKey, LocalDate expiry, int lotSize,
                               LocalDate sessionDate, BigDecimal finalClosePrice,
                               Instant finalPriceFirstAvailableAt, long finalOpenInterestUnits,
                               long tradedVolumeUnits, Instant finalizedOiFirstAvailableAt,
                               Instant verifiedExchangeCloseAt) {}

    private final JdbcTemplate jdbc;

    public FnoVerifiedFinalPriceOiSessionStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /** Erase only expired evidence (V100 trigger forbids edits and early deletion). */
    public int purgeExpired(LocalDate today) {
        return jdbc.update("DELETE FROM fno_verified_final_price_oi_session WHERE retain_until < ?",
            Date.valueOf(Objects.requireNonNull(today)));
    }

    @Scheduled(cron = "0 47 3 * * *", zone = "Asia/Kolkata")
    public void scheduledRetentionPurge() {
        purgeExpired(LocalDate.now(IST));
    }

    /**
     * Results may be empty for absent/revoked entitlements, missing independently
     * captured final values, missing holiday receipts or expired retention.
     * Never substitutes quote values or assumes historical data availability.
     */
    public List<FinalSession> readExactContract(String key, LocalDate from, LocalDate to,
                                                 Instant asOf) {
        if (key == null || !key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || from == null || to == null || asOf == null || from.isAfter(to)
                || to.isAfter(asOf.atZone(IST).toLocalDate())
                || from.isBefore(to.minusDays(30))) {
            throw new IllegalArgumentException("FNO_FINAL_PRICE_OI_READ_SCOPE_INVALID");
        }
        Timestamp cutoff = Timestamp.from(asOf);
        return jdbc.query(SELECT_SQL, (rs, row) -> new FinalSession(
            rs.getString("provider_label"), rs.getString("instrument_key"),
            rs.getString("underlying_key"),
            rs.getObject("expiry_date", LocalDate.class),
            rs.getInt("lot_size"), rs.getObject("session_date", LocalDate.class),
            rs.getBigDecimal("final_close_price"),
            rs.getTimestamp("final_price_first_available_at").toInstant(),
            rs.getLong("final_open_interest_units"),
            rs.getLong("traded_volume_units"),
            rs.getTimestamp("finalized_oi_first_available_at").toInstant(),
            rs.getTimestamp("session_close_at").toInstant()),
            key, Date.valueOf(from), Date.valueOf(to),
            Date.valueOf(asOf.atZone(IST).toLocalDate()),
            cutoff, cutoff, cutoff, cutoff, cutoff, cutoff,
            cutoff, cutoff, cutoff, cutoff,
            cutoff, cutoff, cutoff, cutoff);
    }
}
