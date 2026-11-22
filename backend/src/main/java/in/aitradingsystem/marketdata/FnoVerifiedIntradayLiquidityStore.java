package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Read-only original-event liquidity observations. Sources are not inferred
 * from live LTP or reconstructed from OHLCV; this requires three distinct
 * source-timed receipts with separate quote/OI/volume use grants.
 */
@Component
public class FnoVerifiedIntradayLiquidityStore {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String SQL = """
        SELECT s.provider_code, s.instrument_key, s.underlying_key, s.expiry,
               s.lot_size, s.instrument_kind, s.bid, s.ask,
               s.bid_depth_units, s.ask_depth_units, s.oi_units,
               s.cumulative_session_volume_units, s.exchange_at,
               s.first_observed_at, s.oi_first_observed_at,
               s.volume_first_observed_at, s.session_source_digest,
               s.contract_source_digest, s.quote_source_digest,
               s.oi_source_digest, s.volume_source_digest
        FROM fno_verified_intraday_liquidity_evidence s
        JOIN golden_data_provider p ON p.provider_code = s.provider_code
            AND p.source_kind = 'EXTERNAL'
        JOIN golden_source_rights_receipt quote_rights
            ON quote_rights.receipt_id = s.quote_rights_receipt_id
            AND quote_rights.provider_id = p.provider_id
            AND quote_rights.source_domain = 'nse_fo_intraday_l1_depth'
            AND quote_rights.permitted_use = 'MODEL_INFERENCE'
            AND quote_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt oi_rights
            ON oi_rights.receipt_id = s.oi_rights_receipt_id
            AND oi_rights.provider_id = p.provider_id
            AND oi_rights.source_domain = 'nse_fo_intraday_oi'
            AND oi_rights.permitted_use = 'MODEL_INFERENCE'
            AND oi_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt volume_rights
            ON volume_rights.receipt_id = s.volume_rights_receipt_id
            AND volume_rights.provider_id = p.provider_id
            AND volume_rights.source_domain = 'nse_fo_intraday_volume'
            AND volume_rights.permitted_use = 'MODEL_INFERENCE'
            AND volume_rights.receipt_status = 'VERIFIED'
        WHERE s.instrument_key = ?
          AND s.instrument_kind = 'FUTURE'
          AND s.exchange_session_verified = TRUE
          AND s.first_observed_at >= s.exchange_at
          AND s.expiry = ?
          AND s.lot_size = ?
          AND s.exchange_at >= ?
          AND s.exchange_at <= ?
          AND s.captured_at <= ?
          AND s.first_observed_at <= ?
          AND s.oi_first_observed_at <= ?
          AND s.volume_first_observed_at <= ?
          AND s.retain_until >= ?
          AND quote_rights.valid_from <= ?
          AND (quote_rights.valid_to IS NULL OR quote_rights.valid_to > ?)
          AND quote_rights.verified_at <= ? AND quote_rights.captured_at <= ?
          AND oi_rights.valid_from <= ?
          AND (oi_rights.valid_to IS NULL OR oi_rights.valid_to > ?)
          AND oi_rights.verified_at <= ? AND oi_rights.captured_at <= ?
          AND volume_rights.valid_from <= ?
          AND (volume_rights.valid_to IS NULL OR volume_rights.valid_to > ?)
          AND volume_rights.verified_at <= ? AND volume_rights.captured_at <= ?
        ORDER BY s.exchange_at DESC
        LIMIT 3
        """;

    public record Snapshot(
        String provider, String instrumentKey, String underlyingKey,
        LocalDate expiry, int lotSize, String kind,
        BigDecimal bid, BigDecimal ask, long bidDepthUnits, long askDepthUnits,
        long oiUnits, long cumulativeVolumeUnits, Instant exchangeAt,
        Instant observedAt, Instant oiFirstObservedAt, Instant volumeFirstObservedAt) {}

    /** Original immutable source digests accompany all five distinct domains. */
    public record ReplayProof(Snapshot snapshot, String sessionSourceSha256,
                              String contractSourceSha256, String quoteSourceSha256,
                              String oiSourceSha256, String volumeSourceSha256) {}

    private final JdbcTemplate jdbc;

    public FnoVerifiedIntradayLiquidityStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public List<Snapshot> latestThreeFutures(String key, LocalDate expiry,
                                             int lotSize, Instant asOf) {
        if (key == null || !key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || expiry == null || lotSize < 1 || lotSize > 100000
                || asOf == null || expiry.isBefore(asOf.atZone(IST).toLocalDate())) {
            throw new IllegalArgumentException("FNO_LIQUIDITY_READ_SCOPE_INVALID");
        }
        Timestamp cutoff = Timestamp.from(asOf);
        var reverse = jdbc.query(SQL, (rs, i) -> new Snapshot(
            rs.getString("provider_code"), rs.getString("instrument_key"),
            rs.getString("underlying_key"), rs.getObject("expiry", LocalDate.class),
            rs.getInt("lot_size"), rs.getString("instrument_kind"),
            rs.getBigDecimal("bid"), rs.getBigDecimal("ask"),
            rs.getLong("bid_depth_units"), rs.getLong("ask_depth_units"),
            rs.getLong("oi_units"), rs.getLong("cumulative_session_volume_units"),
            rs.getTimestamp("exchange_at").toInstant(),
            rs.getTimestamp("first_observed_at").toInstant(),
            rs.getTimestamp("oi_first_observed_at").toInstant(),
            rs.getTimestamp("volume_first_observed_at").toInstant()),
            key, Date.valueOf(expiry), lotSize,
            Timestamp.from(asOf.minusSeconds(90)), cutoff,
            cutoff, cutoff, cutoff, cutoff,
            Date.valueOf(asOf.atZone(IST).toLocalDate()),
            cutoff, cutoff, cutoff, cutoff,
            cutoff, cutoff, cutoff, cutoff,
            cutoff, cutoff, cutoff, cutoff);
        if (reverse == null || reverse.isEmpty()) return List.of();
        var forward = new ArrayList<>(reverse);
        Collections.reverse(forward);
        return List.copyOf(forward);
    }

    /**
     * Historical three-observation replay for STR-179. SQL joins all three
     * original rights receipts and tests recorded/verified-at against the
     * supplied historical decision time. These are NOT replayable from daily
     * OHLCV or from broker snapshots captured after that decision.
     */
    public List<ReplayProof> latestThreeReplayFutures(
            String key, LocalDate expiry, int lotSize, Instant asOf) {
        if (key == null || !key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || expiry == null || lotSize < 1 || lotSize > 100000
                || asOf == null || expiry.isBefore(asOf.atZone(IST).toLocalDate()))
            throw new IllegalArgumentException("FNO_LIQUIDITY_READ_SCOPE_INVALID");
        Timestamp cutoff = Timestamp.from(asOf);
        var latestFirst=jdbc.query(SQL, (rs, i) -> new ReplayProof(
            new Snapshot(rs.getString("provider_code"),
                rs.getString("instrument_key"), rs.getString("underlying_key"),
                rs.getObject("expiry", LocalDate.class),rs.getInt("lot_size"),
                rs.getString("instrument_kind"),
                rs.getBigDecimal("bid"),rs.getBigDecimal("ask"),
                rs.getLong("bid_depth_units"),rs.getLong("ask_depth_units"),
                rs.getLong("oi_units"),rs.getLong("cumulative_session_volume_units"),
                rs.getTimestamp("exchange_at").toInstant(),
                rs.getTimestamp("first_observed_at").toInstant(),
                rs.getTimestamp("oi_first_observed_at").toInstant(),
                rs.getTimestamp("volume_first_observed_at").toInstant()),
            rs.getString("session_source_digest"),
            rs.getString("contract_source_digest"),
            rs.getString("quote_source_digest"),
            rs.getString("oi_source_digest"),
            rs.getString("volume_source_digest")),
            key, Date.valueOf(expiry), lotSize,
            Timestamp.from(asOf.minusSeconds(90)),cutoff,
            cutoff,cutoff,cutoff,cutoff,
            Date.valueOf(asOf.atZone(IST).toLocalDate()),
            cutoff,cutoff,cutoff,cutoff,
            cutoff,cutoff,cutoff,cutoff,
            cutoff,cutoff,cutoff,cutoff);
        if (latestFirst == null || latestFirst.isEmpty()) return List.of();
        var chronological = new ArrayList<>(latestFirst);
        Collections.reverse(chronological);
        return List.copyOf(chronological);
    }

    public int purgeExpired(LocalDate today) {
        return jdbc.update(
            "DELETE FROM fno_verified_intraday_liquidity_evidence WHERE retain_until < ?",
            Date.valueOf(Objects.requireNonNull(today)));
    }

    @Scheduled(cron = "0 55 3 * * *", zone = "Asia/Kolkata")
    public void scheduledRetentionPurge() {
        purgeExpired(LocalDate.now(IST));
    }
}
