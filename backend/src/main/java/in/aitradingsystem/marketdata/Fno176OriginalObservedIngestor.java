package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Dormant, deliberately unwired original-source ingestion boundary for STR-176.
 *
 * No controller, scheduler, broker credentials, API client, permission grants,
 * or default "trusted" verifier is registered. An independently reviewed
 * authenticated source adapter must check actual provider bytes and original
 * contract/session metadata before invoking this boundary.
 *
 * Vendor historical candles identify the SESSION, not when a vendor first
 * published them. This class NEVER accepts caller-supplied first-known times.
 * Instead V102 uses the database's current observation time for both source
 * first-available fields, a conservative upper bound on when MintTick knew the
 * values. A future as-of replay must therefore be AFTER the original ingest;
 * retroactive E2E for older hypothetical decisions remains disallowed.
 *
 * This writes no market data unless independent provider rights, original
 * exchange calendar, two domain-specific verified inference receipts, the
 * source adapter and the database all agree. It never certifies a strategy.
 */
public final class Fno176OriginalObservedIngestor {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_ORIGINAL_CANDLE_BYTES = 1_048_576;
    // Matches the existing Upstox published master compressed-file cap.
    private static final int MAX_CONTRACT_MASTER_BYTES = 20 * 1024 * 1024;
    private static final String DIGEST = "[a-f0-9]{64}";

    /**
     * Three raw packets must come directly from the authenticated provider
     * acquisition boundary, never from operational market_candle or user input.
     * Nothing is logged, stored or exported from these byte arrays.
     */
    public record OriginalPackets(String providerCode, byte[] ohlcvPacket,
                                  byte[] finalOiPacket, byte[] contractMasterPacket) {
        public OriginalPackets {
            if (!("UPSTOX_ANALYTICS".equals(providerCode)
                    || "ANGEL_ONE_SMARTAPI".equals(providerCode))
                    || !bounded(ohlcvPacket, MAX_ORIGINAL_CANDLE_BYTES)
                    || !bounded(finalOiPacket, MAX_ORIGINAL_CANDLE_BYTES)
                    || !bounded(contractMasterPacket, MAX_CONTRACT_MASTER_BYTES))
                throw new IllegalArgumentException("INVALID_ORIGINAL_SOURCE_PACKETS");
            ohlcvPacket = ohlcvPacket.clone();
            finalOiPacket = finalOiPacket.clone();
            contractMasterPacket = contractMasterPacket.clone();
        }
        @Override public byte[] ohlcvPacket() { return ohlcvPacket.clone(); }
        @Override public byte[] finalOiPacket() { return finalOiPacket.clone(); }
        @Override public byte[] contractMasterPacket() { return contractMasterPacket.clone(); }
    }

    /** A source adapter must independently parse and verify ALL three packets. */
    @FunctionalInterface
    public interface IndependentlyVerifiedSource {
        Optional<VerifiedDay> verify(OriginalPackets original);
    }

    /**
     * Only original vendor-derived contract and bar facts. No fabricated
     * publication/capture/first-known timestamps are accepted from a caller.
     */
    public record VerifiedDay(
        String providerCode, String instrumentKey, String underlyingKey,
        LocalDate listedFrom, LocalDate expiry, int lotSize, LocalDate sessionDate,
        BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
        long volumeUnits, long finalOiUnits,
        UUID calendarEvidenceId, UUID ohlcvInferenceRightsId,
        UUID oiInferenceRightsId, LocalDate retainUntil) {}

    public record IngestResult(String state, boolean originalSessionStored,
                               boolean independentHistoricalE2ECertified,
                               boolean paperOrdersPlaced, boolean liveOrdersPlaced) {}

    private static final String INSERT = """
        WITH observed AS (
            SELECT clock_timestamp() AS accepted_at
        ), live_rights AS (
            SELECT r.*
            FROM golden_source_rights_receipt r
            CROSS JOIN observed observed
            WHERE r.receipt_status = 'VERIFIED'
              AND r.verified_at IS NOT NULL AND r.verified_by IS NOT NULL
              AND r.revoked_at IS NULL
              AND r.captured_at <= observed.accepted_at
              AND r.verified_at <= observed.accepted_at
              AND r.valid_from <= observed.accepted_at
              AND (r.valid_to IS NULL OR r.valid_to > observed.accepted_at)
              AND NOT EXISTS (
                  SELECT 1 FROM golden_source_rights_receipt revoked
                  WHERE revoked.provider_id = r.provider_id
                    AND revoked.source_domain = r.source_domain
                    AND revoked.permitted_use = r.permitted_use
                    AND revoked.receipt_status = 'REVOKED'
                    AND revoked.revoked_at <= observed.accepted_at
                    AND revoked.captured_at <= observed.accepted_at
              )
        )
        INSERT INTO fno_verified_completed_ohlcv_oi_session (
            provider_code, instrument_key, underlying_key, listed_from,
            expiry, lot_size, session_date, open_price, high_price, low_price,
            close_price, traded_volume_units, final_open_interest_units,
            ohlcv_first_available_at, oi_first_available_at,
            ohlcv_source_digest, oi_source_digest, listing_source_digest,
            calendar_evidence_id, ohlcv_rights_receipt_id, oi_rights_receipt_id,
            retain_until, captured_at)
        SELECT :provider, :instrument, :underlying, :listed, :expiry,
               :lot, :session, :open, :high, :low, :close, :volume, :oi,
               observed.accepted_at, observed.accepted_at,
               :ohlcvHash, :oiHash, :listingHash,
               cal.evidence_id, ohlcv.receipt_id, oi.receipt_id,
               :retain, observed.accepted_at
        FROM golden_data_provider p
        CROSS JOIN observed observed
        JOIN live_rights ohlcv ON ohlcv.provider_id = p.provider_id
            AND ohlcv.receipt_id = :ohlcvRights
            AND ohlcv.source_domain = 'nse_fo_completed_ohlcv'
            AND ohlcv.permitted_use = 'MODEL_INFERENCE'
        JOIN live_rights oi ON oi.provider_id = p.provider_id
            AND oi.receipt_id = :oiRights
            AND oi.source_domain = 'nse_fo_final_oi'
            AND oi.permitted_use = 'MODEL_INFERENCE'
        JOIN golden_historical_exchange_session_evidence cal
            ON cal.evidence_id = :calendar
            AND cal.provider_id = p.provider_id
            AND cal.source_domain = 'nse_fo_trading_calendar'
            AND cal.exchange = 'NSE'
            AND cal.session_date = :session
            AND cal.session_status = 'TRADING'
        WHERE p.provider_code = :provider AND p.source_kind = 'EXTERNAL'
          AND ohlcv.receipt_id <> oi.receipt_id
          AND cal.source_published_at <= cal.first_available_at
          AND cal.first_available_at <= cal.verified_at
          AND cal.verified_at <= cal.captured_at
          AND cal.captured_at <= observed.accepted_at
          AND cal.session_close_at <= observed.accepted_at
          AND cal.verified_by IS NOT NULL AND length(trim(cal.verified_by)) > 0
          AND :retain >= (observed.accepted_at AT TIME ZONE 'Asia/Kolkata')::date
          AND EXISTS (
              SELECT 1 FROM live_rights raw
              WHERE raw.provider_id = p.provider_id
                AND raw.source_domain = 'nse_fo_completed_ohlcv'
                AND raw.permitted_use = 'RAW_RETENTION'
          )
          AND EXISTS (
              SELECT 1 FROM live_rights raw
              WHERE raw.provider_id = p.provider_id
                AND raw.source_domain = 'nse_fo_final_oi'
                AND raw.permitted_use = 'RAW_RETENTION'
          )
          AND EXISTS (
              SELECT 1 FROM live_rights raw
              WHERE raw.provider_id = p.provider_id
                AND raw.source_domain = 'nse_fo_trading_calendar'
                AND raw.permitted_use = 'RAW_RETENTION'
          )
          AND EXISTS (
              SELECT 1 FROM live_rights usage
              WHERE usage.provider_id = p.provider_id
                AND usage.source_domain = 'nse_fo_trading_calendar'
                AND usage.permitted_use = 'MODEL_INFERENCE'
          )
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final GoldenSourceRightsRepository rights;
    private final IndependentlyVerifiedSource verifier;
    private final Clock clock;

    public Fno176OriginalObservedIngestor(
            NamedParameterJdbcTemplate jdbc, GoldenSourceRightsRepository rights,
            IndependentlyVerifiedSource verifier, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.rights = Objects.requireNonNull(rights);
        this.verifier = Objects.requireNonNull(verifier);
        this.clock = Objects.requireNonNull(clock);
    }

    public IngestResult ingest(OriginalPackets original) {
        if (original == null) return blocked("SOURCE_PACKETS_UNAVAILABLE");
        var at = clock.instant();
        try {
            // Check BEFORE parsing or touching SQL; revocations win.
            for (var domain : new String[] {
                    "nse_fo_completed_ohlcv", "nse_fo_final_oi",
                    "nse_fo_trading_calendar"}) {
                if (!rights.isPermitted(original.providerCode(), domain,
                        GoldenSourceRightsRepository.Use.RAW_RETENTION, at)
                        || !rights.isPermitted(original.providerCode(), domain,
                        GoldenSourceRightsRepository.Use.MODEL_INFERENCE, at))
                    return blocked("ORIGINAL_RESEARCH_USE_NOT_AUTHORIZED");
            }
        } catch (RuntimeException rejected) {
            return blocked("ORIGINAL_RESEARCH_USE_NOT_AUTHORIZED");
        }

        final VerifiedDay day;
        try {
            day = verifier.verify(original).orElse(null);
        } catch (RuntimeException untrusted) {
            return blocked("INDEPENDENT_SOURCE_VERIFICATION_FAILED");
        }
        if (!valid(day, original, at.atZone(IST).toLocalDate()))
            return blocked("INDEPENDENT_SOURCE_VERIFICATION_FAILED");

        try {
            var bind = new MapSqlParameterSource()
                .addValue("provider", day.providerCode())
                .addValue("instrument", day.instrumentKey())
                .addValue("underlying", day.underlyingKey())
                .addValue("listed", java.sql.Date.valueOf(day.listedFrom()))
                .addValue("expiry", java.sql.Date.valueOf(day.expiry()))
                .addValue("lot", day.lotSize())
                .addValue("session", java.sql.Date.valueOf(day.sessionDate()))
                .addValue("open", day.open()).addValue("high", day.high())
                .addValue("low", day.low()).addValue("close", day.close())
                .addValue("volume", day.volumeUnits())
                .addValue("oi", day.finalOiUnits())
                .addValue("ohlcvHash", sha256(original.ohlcvPacket()))
                .addValue("oiHash", sha256(original.finalOiPacket()))
                .addValue("listingHash", sha256(original.contractMasterPacket()))
                .addValue("calendar", day.calendarEvidenceId())
                .addValue("ohlcvRights", day.ohlcvInferenceRightsId())
                .addValue("oiRights", day.oiInferenceRightsId())
                .addValue("retain", java.sql.Date.valueOf(day.retainUntil()));
            int rows = jdbc.update(INSERT, bind);
            return rows == 1
                ? new IngestResult("ORIGINAL_OBSERVED_SOURCE_STORED_NOT_E2E",
                                   true, false, false, false)
                : blocked("ORIGINAL_RECEIPTS_OR_CALENDAR_MISSING");
        } catch (DataAccessException | IllegalArgumentException sourceRejected) {
            // No raw SQL exception/provider payload ever leaves this boundary.
            return blocked("ORIGINAL_SOURCE_INSERT_REJECTED");
        }
    }

    private static boolean valid(VerifiedDay b, OriginalPackets p, LocalDate today) {
        if (b == null || !p.providerCode().equals(b.providerCode())
                || b.instrumentKey() == null
                || !b.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || b.underlyingKey() == null
                || !b.underlyingKey().matches(
                    "(NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})")
                || b.listedFrom() == null || b.expiry() == null
                || b.sessionDate() == null || b.retainUntil() == null
                || b.sessionDate().isBefore(b.listedFrom())
                || !b.sessionDate().isBefore(b.expiry())
                || !b.sessionDate().isBefore(today)
                || b.retainUntil().isBefore(today)
                || b.retainUntil().isAfter(b.sessionDate().plusDays(365))
                || b.lotSize() < 1 || b.lotSize() > 100000
                || b.volumeUnits() < 0 || b.finalOiUnits() <= 0
                || b.calendarEvidenceId() == null
                || b.ohlcvInferenceRightsId() == null || b.oiInferenceRightsId() == null
                || b.ohlcvInferenceRightsId().equals(b.oiInferenceRightsId()))
            return false;
        return price(b.open()) && price(b.high()) && price(b.low())
            && price(b.close())
            && b.high().compareTo(b.open()) >= 0
            && b.high().compareTo(b.close()) >= 0
            && b.low().compareTo(b.open()) <= 0
            && b.low().compareTo(b.close()) <= 0;
    }
    private static boolean price(BigDecimal v) {
        return v != null && v.signum() > 0 && v.scale() <= 6
            && v.precision() - v.scale() <= 14;
    }
    private static boolean bounded(byte[] value, int limit) {
        return value != null && value.length >= 1 && value.length <= limit;
    }
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("HASH_PRIMITIVE_MISSING");
        }
    }
    private static IngestResult blocked(String state) {
        return new IngestResult(state, false, false, false, false);
    }
}
