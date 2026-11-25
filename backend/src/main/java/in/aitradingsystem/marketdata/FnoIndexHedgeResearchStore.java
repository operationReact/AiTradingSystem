package in.aitradingsystem.marketdata;

import in.aitradingsystem.tradingrooms.FnoFuturesHedgeOverlayV1;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Independent, account-scoped evidence that portfolio beta, existing hedges,
 * broker reserves, stress costs and index futures L1 depth were actually
 * reconciled. Never infer exposure from fund balances or assume a broker fill.
 * V103 is empty until explicit attested source ingestion exists.
 */
@Component
public class FnoIndexHedgeResearchStore {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String SQL = """
        SELECT p.provider_code, p.account_scope, p.index_key, p.future_key,
               p.futures_expiry, p.lot_size, p.signed_beta_exposure_inr,
               p.exposure_calculated_at, p.exposure_first_observed_at,
               p.future_bid, p.future_ask, p.future_bid_depth_units,
               p.future_ask_depth_units, p.future_open_interest_units,
               p.exchange_at, p.market_first_observed_at, p.available_cash_inr,
               p.initial_margin_per_lot_inr, p.variation_reserve_per_lot_inr,
               p.index_shock_bps, p.basis_stress_per_lot_inr,
               p.all_in_round_trip_cost_per_lot_inr, p.max_hedge_lots,
               p.max_bid_ask_spread_bps, p.minimum_risk_reduction_bps
        FROM fno_index_hedge_research_evidence p
        JOIN golden_data_provider provider
            ON provider.provider_code = p.provider_code
            AND provider.source_kind = 'EXTERNAL'
        JOIN golden_source_rights_receipt market_rights
            ON market_rights.receipt_id = p.market_rights_receipt_id
            AND market_rights.provider_id = provider.provider_id
            AND market_rights.source_domain = 'nse_fo_index_future_market_depth'
            AND market_rights.permitted_use = 'MODEL_INFERENCE'
            AND market_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt portfolio_rights
            ON portfolio_rights.receipt_id = p.portfolio_rights_receipt_id
            AND portfolio_rights.provider_id = provider.provider_id
            AND portfolio_rights.source_domain = 'portfolio_reconciled_beta_exposure'
            AND portfolio_rights.permitted_use = 'MODEL_INFERENCE'
            AND portfolio_rights.receipt_status = 'VERIFIED'
        WHERE p.account_scope = ?
          AND p.index_key = ?
          AND p.future_key = ?
          AND p.futures_expiry = ?
          AND p.lot_size = ?
          AND p.captured_at <= ?
          AND p.exposure_first_observed_at <= ?
          AND p.market_first_observed_at <= ?
          AND p.valid_until >= ?
          AND p.retain_until >= ?
          AND market_rights.valid_from <= ?
          AND (market_rights.valid_to IS NULL OR market_rights.valid_to > ?)
          AND market_rights.verified_at <= ? AND market_rights.captured_at <= ?
          AND portfolio_rights.valid_from <= ?
          AND (portfolio_rights.valid_to IS NULL OR portfolio_rights.valid_to > ?)
          AND portfolio_rights.verified_at <= ? AND portfolio_rights.captured_at <= ?
        ORDER BY p.market_first_observed_at DESC
        LIMIT 2
        """;

    public record Proof(String provider, String accountScope, String indexKey,
                        String futureKey, LocalDate expiry, int lotSize,
                        FnoFuturesHedgeOverlayV1.Portfolio portfolio,
                        FnoFuturesHedgeOverlayV1.MarketQuote market,
                        FnoFuturesHedgeOverlayV1.Funding funding,
                        FnoFuturesHedgeOverlayV1.Stress stress) {}

    private final JdbcTemplate jdbc;

    public FnoIndexHedgeResearchStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public Optional<Proof> oneCurrentProof(String accountScope, String indexKey,
                                           String futureKey, LocalDate expiry,
                                           int lotSize, Instant asOf) {
        if (accountScope == null || !accountScope.matches("[A-Za-z0-9_-]{8,80}")
                || indexKey == null || !indexKey.matches("NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64}")
                || futureKey == null || !futureKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || expiry == null || lotSize < 1 || lotSize > 100000
                || asOf == null || expiry.isBefore(asOf.atZone(IST).toLocalDate().plusDays(3))) {
            throw new IllegalArgumentException("FNO_HEDGE_PROOF_READ_SCOPE_INVALID");
        }
        Timestamp at = Timestamp.from(asOf);
        List<Proof> rows = jdbc.query(SQL, (rs, i) -> new Proof(
            rs.getString("provider_code"), rs.getString("account_scope"),
            rs.getString("index_key"), rs.getString("future_key"),
            rs.getObject("futures_expiry", LocalDate.class), rs.getInt("lot_size"),
            new FnoFuturesHedgeOverlayV1.Portfolio(
                rs.getString("index_key"), rs.getBigDecimal("signed_beta_exposure_inr"),
                rs.getTimestamp("exposure_calculated_at").toInstant(),
                rs.getTimestamp("exposure_first_observed_at").toInstant(),
                true, true, true),
            new FnoFuturesHedgeOverlayV1.MarketQuote(
                rs.getString("future_key"), rs.getString("provider_code"),
                rs.getBigDecimal("future_bid"), rs.getBigDecimal("future_ask"),
                rs.getLong("future_bid_depth_units"), rs.getLong("future_ask_depth_units"),
                rs.getLong("future_open_interest_units"),
                rs.getTimestamp("exchange_at").toInstant(),
                rs.getTimestamp("market_first_observed_at").toInstant(), true, true),
            new FnoFuturesHedgeOverlayV1.Funding(
                rs.getBigDecimal("available_cash_inr"),
                rs.getBigDecimal("initial_margin_per_lot_inr"),
                rs.getBigDecimal("variation_reserve_per_lot_inr"), true, true),
            new FnoFuturesHedgeOverlayV1.Stress(
                rs.getInt("index_shock_bps"),
                rs.getBigDecimal("basis_stress_per_lot_inr"),
                rs.getBigDecimal("all_in_round_trip_cost_per_lot_inr"),
                rs.getInt("max_hedge_lots"),
                rs.getInt("max_bid_ask_spread_bps"),
                rs.getInt("minimum_risk_reduction_bps"), true, true)),
            accountScope, indexKey, futureKey, Date.valueOf(expiry), lotSize,
            at, at, at, at, Date.valueOf(asOf.atZone(IST).toLocalDate()),
            at, at, at, at, at, at, at, at);
        // Multiple simultaneously current portfolio calculations/depth records
        // are ambiguous. Never select the most profitable hedge conveniently.
        if (rows == null || rows.size() != 1) return Optional.empty();
        Proof proof = rows.getFirst();
        if (proof == null || !accountScope.equals(proof.accountScope())
                || !indexKey.equals(proof.indexKey())
                || !futureKey.equals(proof.futureKey())
                || !expiry.equals(proof.expiry()) || lotSize != proof.lotSize()
                || !"UPSTOX_V3".equals(proof.provider())
                || proof.portfolio() == null || proof.market() == null
                || proof.funding() == null || proof.stress() == null) {
            return Optional.empty();
        }
        return Optional.of(proof);
    }

    public int purgeExpired(LocalDate today) {
        return jdbc.update(
            "DELETE FROM fno_index_hedge_research_evidence WHERE retain_until < ?",
            Date.valueOf(Objects.requireNonNull(today)));
    }

    @Scheduled(cron = "0 53 3 * * *", zone = "Asia/Kolkata")
    public void scheduledRetentionPurge() {
        purgeExpired(LocalDate.now(IST));
    }
}
