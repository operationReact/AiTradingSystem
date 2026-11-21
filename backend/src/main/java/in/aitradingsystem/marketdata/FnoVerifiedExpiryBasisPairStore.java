package in.aitradingsystem.marketdata;

import in.aitradingsystem.tradingrooms.FnoExpiryBasisReversionV1;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Strict read-only first-known NEAR-CASH-CLOSE paired equity/futures quotes.
 * This is NOT an exchange settlement-price or broker execution source.
 * No inferred carry, dividend, physical delivery or fee assumptions.
 * The V105 evidence table is empty and requires independent source receipts.
 */
@Component
public class FnoVerifiedExpiryBasisPairStore {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final String SQL = """
        SELECT s.provider_code, s.spot_key, s.futures_key, s.listed_from,
               s.expiry, s.lot_size, s.session_date, s.spot_bid, s.spot_ask,
               s.futures_bid, s.futures_ask, s.spot_exchange_at,
               s.futures_exchange_at, s.spot_quote_first_observed_at,
               s.futures_quote_first_observed_at, s.spot_final_first_available_at,
               s.futures_final_first_available_at, cash_cal.session_close_at AS cash_close_at,
               future_cal.session_close_at AS future_close_at,
               s.financing_annual_bps, s.dividend_annual_bps, s.round_trip_cost_bps,
               s.min_converging_steps, s.min_convergence_bps,
               s.min_after_cost_residual_bps, s.max_spread_bps,
               s.max_session_move_bps, s.dividend_events_verified,
               s.corporate_actions_verified, s.physical_delivery_reviewed,
               s.costs_reviewed, s.deliverable_spot_verified,
               s.contract_source_digest, s.carry_cost_source_digest
        FROM fno_verified_expiry_basis_pair_evidence s
        JOIN golden_data_provider provider
          ON provider.provider_code = s.provider_code
          AND provider.source_kind = 'EXTERNAL'
        JOIN golden_historical_exchange_session_evidence cash_cal
          ON cash_cal.evidence_id = s.spot_calendar_evidence_id
          AND cash_cal.provider_id = provider.provider_id
          AND cash_cal.source_domain = 'nse_eq_trading_calendar'
          AND cash_cal.exchange = 'NSE'
          AND cash_cal.session_date = s.session_date
          AND cash_cal.session_status = 'TRADING'
        JOIN golden_historical_exchange_session_evidence future_cal
          ON future_cal.evidence_id = s.futures_calendar_evidence_id
          AND future_cal.provider_id = provider.provider_id
          AND future_cal.source_domain = 'nse_fo_trading_calendar'
          AND future_cal.exchange = 'NSE'
          AND future_cal.session_date = s.session_date
          AND future_cal.session_status = 'TRADING'
        JOIN golden_source_rights_receipt spot_rights
          ON spot_rights.receipt_id = s.spot_rights_receipt_id
          AND spot_rights.provider_id = provider.provider_id
          AND spot_rights.source_domain = 'nse_eq_near_close_quote'
          AND spot_rights.permitted_use = 'MODEL_INFERENCE'
          AND spot_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt futures_rights
          ON futures_rights.receipt_id = s.futures_rights_receipt_id
          AND futures_rights.provider_id = provider.provider_id
          AND futures_rights.source_domain = 'nse_fo_near_close_quote'
          AND futures_rights.permitted_use = 'MODEL_INFERENCE'
          AND futures_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt carry_rights
          ON carry_rights.receipt_id = s.carry_cost_rights_receipt_id
          AND carry_rights.provider_id = provider.provider_id
          AND carry_rights.source_domain = 'nse_eq_carry_delivery_costs'
          AND carry_rights.permitted_use = 'MODEL_INFERENCE'
          AND carry_rights.receipt_status = 'VERIFIED'
        WHERE s.spot_key = ?
          AND s.futures_key = ?
          AND s.expiry = ?
          AND s.lot_size = ?
          AND s.session_date BETWEEN ? AND ?
          AND s.retain_until >= ?
          AND s.captured_at <= ?
          AND s.spot_quote_first_observed_at <= ?
          AND s.futures_quote_first_observed_at <= ?
          AND s.spot_final_first_available_at <= ?
          AND s.futures_final_first_available_at <= ?
          AND s.spot_exchange_at >= cash_cal.session_close_at - INTERVAL '5 minutes'
          AND s.spot_exchange_at <= cash_cal.session_close_at
          AND s.futures_exchange_at >= cash_cal.session_close_at - INTERVAL '5 minutes'
          AND s.futures_exchange_at <= cash_cal.session_close_at
          AND s.spot_final_first_available_at >= cash_cal.session_close_at
          AND s.futures_final_first_available_at >= future_cal.session_close_at
          AND cash_cal.first_available_at <= ?
          AND cash_cal.verified_at <= ? AND cash_cal.captured_at <= ?
          AND future_cal.first_available_at <= ?
          AND future_cal.verified_at <= ? AND future_cal.captured_at <= ?
          AND spot_rights.valid_from <= ?
          AND (spot_rights.valid_to IS NULL OR spot_rights.valid_to > ?)
          AND spot_rights.verified_at <= ? AND spot_rights.captured_at <= ?
          AND futures_rights.valid_from <= ?
          AND (futures_rights.valid_to IS NULL OR futures_rights.valid_to > ?)
          AND futures_rights.verified_at <= ? AND futures_rights.captured_at <= ?
          AND carry_rights.valid_from <= ?
          AND (carry_rights.valid_to IS NULL OR carry_rights.valid_to > ?)
          AND carry_rights.verified_at <= ? AND carry_rights.captured_at <= ?
        ORDER BY s.session_date, s.provider_code
        LIMIT 40
        """;

    /** Observed original timed paired quote, separate session-close custody and
     *  source-reviewed contract/carry assumptions. Both legs are indicative L1. */
    public record PairProof(
        String provider, String spotKey, String futuresKey, LocalDate listedFrom,
        LocalDate expiry, int lotSize, LocalDate sessionDate,
        BigDecimal spotBid, BigDecimal spotAsk,
        BigDecimal futuresBid, BigDecimal futuresAsk,
        Instant spotQuoteAt, Instant futuresQuoteAt,
        Instant spotQuoteFirstObservedAt, Instant futuresQuoteFirstObservedAt,
        Instant spotFinalFirstAvailableAt, Instant futuresFinalFirstAvailableAt,
        Instant cashSessionCloseAt, Instant futuresSessionCloseAt,
        boolean deliverableSpotVerified, String contractSourceDigest,
        String carryCostSourceDigest,
        FnoExpiryBasisReversionV1.Assumptions assumptions) {}

    private final JdbcTemplate jdbc;

    public FnoVerifiedExpiryBasisPairStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /**
     * Rejects invalid identity, unbounded windows and future-as-of requests.
     * SQL separately enforces provider-scoped independently verified spot/future
     * quotes, carry/delivery rights, both exchange calendars, capture and first
     * publication times. No raw quote data, fees or rights are synthesized.
     */
    public List<PairProof> readExactPair(String spotKey, String futuresKey,
                                        LocalDate expiry, int lotSize,
                                        LocalDate from, LocalDate to, Instant asOf) {
        if (spotKey == null || !spotKey.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")
                || futuresKey == null || !futuresKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || expiry == null || lotSize < 1 || lotSize > 100000
                || from == null || to == null || asOf == null
                || from.isAfter(to) || from.isBefore(to.minusDays(21))
                || to.isAfter(asOf.atZone(IST).toLocalDate())) {
            throw new IllegalArgumentException("FNO_EXPIRY_BASIS_SOURCE_SCOPE_INVALID");
        }
        Object[] bind = new Object[30];
        bind[0] = spotKey;
        bind[1] = futuresKey;
        bind[2] = Date.valueOf(expiry);
        bind[3] = lotSize;
        bind[4] = Date.valueOf(from);
        bind[5] = Date.valueOf(to);
        bind[6] = Date.valueOf(asOf.atZone(IST).toLocalDate());
        Timestamp instant = Timestamp.from(asOf);
        Arrays.fill(bind, 7, bind.length, instant);
        return jdbc.query(SQL, (rs, i) -> new PairProof(
            rs.getString("provider_code"), rs.getString("spot_key"),
            rs.getString("futures_key"),
            rs.getObject("listed_from", LocalDate.class),
            rs.getObject("expiry", LocalDate.class), rs.getInt("lot_size"),
            rs.getObject("session_date", LocalDate.class),
            rs.getBigDecimal("spot_bid"), rs.getBigDecimal("spot_ask"),
            rs.getBigDecimal("futures_bid"), rs.getBigDecimal("futures_ask"),
            rs.getTimestamp("spot_exchange_at").toInstant(),
            rs.getTimestamp("futures_exchange_at").toInstant(),
            rs.getTimestamp("spot_quote_first_observed_at").toInstant(),
            rs.getTimestamp("futures_quote_first_observed_at").toInstant(),
            rs.getTimestamp("spot_final_first_available_at").toInstant(),
            rs.getTimestamp("futures_final_first_available_at").toInstant(),
            rs.getTimestamp("cash_close_at").toInstant(),
            rs.getTimestamp("future_close_at").toInstant(),
            rs.getBoolean("deliverable_spot_verified"),
            rs.getString("contract_source_digest"),
            rs.getString("carry_cost_source_digest"),
            new FnoExpiryBasisReversionV1.Assumptions(
                rs.getBigDecimal("financing_annual_bps"),
                rs.getBigDecimal("dividend_annual_bps"),
                rs.getBigDecimal("round_trip_cost_bps"),
                rs.getInt("min_converging_steps"),
                rs.getInt("min_convergence_bps"),
                rs.getInt("min_after_cost_residual_bps"),
                rs.getInt("max_spread_bps"), rs.getInt("max_session_move_bps"),
                rs.getBoolean("dividend_events_verified"),
                rs.getBoolean("corporate_actions_verified"),
                rs.getBoolean("physical_delivery_reviewed"),
                rs.getBoolean("costs_reviewed"))), bind);
    }

    public int purgeExpired(LocalDate today) {
        return jdbc.update(
            "DELETE FROM fno_verified_expiry_basis_pair_evidence WHERE retain_until < ?",
            Date.valueOf(Objects.requireNonNull(today)));
    }

    @Scheduled(cron = "0 57 3 * * *", zone = "Asia/Kolkata")
    public void scheduledRetentionPurge() {
        purgeExpired(LocalDate.now(IST));
    }
}
