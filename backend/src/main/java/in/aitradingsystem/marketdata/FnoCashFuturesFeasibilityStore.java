package in.aitradingsystem.marketdata;

import in.aitradingsystem.tradingrooms.FnoCashFuturesArbitrageResearchV1;
import in.aitradingsystem.tradingrooms.FnoFuturesBasisCarryV1;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Read-only, broker-account-scoped STR-175 research source.
 *
 * The existing V3 quote feed supplies live BID/ASK; it does NOT supply market
 * depth, capital, margin or verified delivery/cost assumptions. Those must be
 * separately attested to the immutable V101 evidence store. Absence, duplicates,
 * expired receipts or conflicting provider/account/contract block the result.
 * No broker orders, position mutation, market data polling or default capital.
 */
@Component
public class FnoCashFuturesFeasibilityStore {
    private static final String SQL = """
        SELECT e.provider_code, e.account_scope, e.spot_key, e.future_key,
               e.futures_expiry, e.lot_size, e.spot_ask_depth_units,
               e.futures_bid_depth_units, e.spot_ask_at_depth, e.futures_bid_at_depth,
               e.depth_exchange_at, e.depth_first_available_at,
               e.funding_annual_bps, e.dividend_annual_bps,
               e.stock_borrow_annual_bps, e.basis_round_trip_cost_bps,
               e.basis_min_net_edge_bps, e.available_cash_inr,
               e.futures_initial_margin_inr, e.stressed_variation_margin_inr,
               e.delivery_buffer_inr, e.extra_slippage_per_share_inr,
               e.incremental_tax_fees_per_lot_inr, e.dividend_uncertainty_per_lot_inr,
               e.corporate_action_buffer_per_lot_inr, e.min_stressed_net_per_lot_inr
        FROM fno_cash_futures_feasibility_evidence e
        JOIN golden_data_provider p
          ON p.provider_code = e.provider_code AND p.source_kind = 'EXTERNAL'
        JOIN golden_source_rights_receipt spot_rights
          ON spot_rights.receipt_id = e.spot_rights_receipt_id
          AND spot_rights.provider_id = p.provider_id
          AND spot_rights.source_domain = 'nse_eq_quote_depth'
          AND spot_rights.permitted_use = 'MODEL_INFERENCE'
          AND spot_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt future_rights
          ON future_rights.receipt_id = e.futures_rights_receipt_id
          AND future_rights.provider_id = p.provider_id
          AND future_rights.source_domain = 'nse_fo_quote_depth'
          AND future_rights.permitted_use = 'MODEL_INFERENCE'
          AND future_rights.receipt_status = 'VERIFIED'
        WHERE e.account_scope = ?
          AND e.spot_key = ?
          AND e.future_key = ?
          AND e.futures_expiry = ?
          AND e.lot_size = ?
          AND e.depth_first_available_at <= ?
          AND e.captured_at <= ?
          AND e.valid_until >= ?
          AND e.retain_until >= ?
          AND spot_rights.valid_from <= ?
          AND (spot_rights.valid_to IS NULL OR spot_rights.valid_to > ?)
          AND spot_rights.verified_at <= ?
          AND spot_rights.captured_at <= ?
          AND future_rights.valid_from <= ?
          AND (future_rights.valid_to IS NULL OR future_rights.valid_to > ?)
          AND future_rights.verified_at <= ?
          AND future_rights.captured_at <= ?
        ORDER BY e.depth_first_available_at DESC
        LIMIT 2
        """;

    public record Proof(String provider, String accountScope, String spotKey, String futureKey,
                        LocalDate expiry, int lotSize, long spotAskDepthUnits,
                        long futuresBidDepthUnits, BigDecimal spotAskAtDepth,
                        BigDecimal futuresBidAtDepth, Instant depthExchangeAt,
                        Instant depthFirstAvailableAt,
                        FnoFuturesBasisCarryV1.Assumptions basis,
                        FnoCashFuturesArbitrageResearchV1.Capital capital,
                        FnoCashFuturesArbitrageResearchV1.AdditionalCosts costs) {}

    private final JdbcTemplate jdbc;

    public FnoCashFuturesFeasibilityStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /** Bound short-lived account-specific funding/depth custody: erase after <=7d. */
    public int purgeExpired(LocalDate today) {
        return jdbc.update("DELETE FROM fno_cash_futures_feasibility_evidence WHERE retain_until < ?",
            Date.valueOf(Objects.requireNonNull(today)));
    }

    @Scheduled(cron = "0 49 3 * * *", zone = "Asia/Kolkata")
    public void scheduledRetentionPurge() {
        purgeExpired(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
    }

    public Optional<Proof> currentSingleProof(String accountScope, String spotKey,
                                               String futureKey, LocalDate expiry,
                                               int lotSize, Instant asOf) {
        if (accountScope == null || !accountScope.matches("[A-Za-z0-9_-]{8,80}")
                || spotKey == null || !spotKey.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")
                || futureKey == null || !futureKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || expiry == null || lotSize < 1 || lotSize > 100000
                || asOf == null || expiry.isBefore(asOf.atZone(
                    java.time.ZoneId.of("Asia/Kolkata")).toLocalDate().plusDays(3))) {
            throw new IllegalArgumentException("CASH_FUT_PROOF_SCOPE_INVALID");
        }
        Timestamp asOfSql = Timestamp.from(asOf);
        List<Proof> rows = jdbc.query(SQL, (rs, index) -> new Proof(
            rs.getString("provider_code"), rs.getString("account_scope"),
            rs.getString("spot_key"), rs.getString("future_key"),
            rs.getObject("futures_expiry", LocalDate.class),
            rs.getInt("lot_size"), rs.getLong("spot_ask_depth_units"),
            rs.getLong("futures_bid_depth_units"), rs.getBigDecimal("spot_ask_at_depth"),
            rs.getBigDecimal("futures_bid_at_depth"),
            rs.getTimestamp("depth_exchange_at").toInstant(),
            rs.getTimestamp("depth_first_available_at").toInstant(),
            new FnoFuturesBasisCarryV1.Assumptions(
                rs.getBigDecimal("funding_annual_bps"),
                rs.getBigDecimal("dividend_annual_bps"),
                rs.getBigDecimal("stock_borrow_annual_bps"),
                rs.getBigDecimal("basis_round_trip_cost_bps"),
                rs.getBigDecimal("basis_min_net_edge_bps")),
            new FnoCashFuturesArbitrageResearchV1.Capital(
                rs.getBigDecimal("available_cash_inr"),
                rs.getBigDecimal("futures_initial_margin_inr"),
                rs.getBigDecimal("stressed_variation_margin_inr"),
                rs.getBigDecimal("delivery_buffer_inr"), true, true, true),
            new FnoCashFuturesArbitrageResearchV1.AdditionalCosts(
                rs.getBigDecimal("extra_slippage_per_share_inr"),
                rs.getBigDecimal("incremental_tax_fees_per_lot_inr"),
                rs.getBigDecimal("dividend_uncertainty_per_lot_inr"),
                rs.getBigDecimal("corporate_action_buffer_per_lot_inr"),
                rs.getBigDecimal("min_stressed_net_per_lot_inr"), true, true, true, true)),
            accountScope, spotKey, futureKey, Date.valueOf(expiry), lotSize,
            asOfSql, asOfSql, asOfSql,
            Date.valueOf(asOf.atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate()),
            asOfSql, asOfSql, asOfSql, asOfSql,
            asOfSql, asOfSql, asOfSql, asOfSql);
        // Never choose a convenient row when two simultaneously eligible but
        // potentially conflicting financial assessments exist.
        if (rows == null || rows.size() != 1) return Optional.empty();
        Proof proof = rows.getFirst();
        if (proof == null || !Objects.equals(accountScope, proof.accountScope())
                || !Objects.equals(spotKey, proof.spotKey())
                || !Objects.equals(futureKey, proof.futureKey())
                || !Objects.equals(expiry, proof.expiry()) || lotSize != proof.lotSize()
                || proof.depthExchangeAt() == null || proof.depthFirstAvailableAt() == null
                || proof.depthExchangeAt().isAfter(proof.depthFirstAvailableAt())
                || proof.depthFirstAvailableAt().isAfter(asOf)
                || proof.depthExchangeAt().isAfter(asOf)
                || Duration.between(proof.depthExchangeAt(), asOf).isNegative()
                || Duration.between(proof.depthExchangeAt(), asOf).compareTo(
                    Duration.ofSeconds(15)) > 0) {
            return Optional.empty();
        }
        return Optional.of(proof);
    }
}
