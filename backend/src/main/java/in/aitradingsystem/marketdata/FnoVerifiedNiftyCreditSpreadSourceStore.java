package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Read-only genuine-source three-leg NIFTY credit-spread evidence.
 * An original-time index tick and both simultaneously quoted NSE_FO options
 * are separately research-rights gated, with recorded full-lot depth/OI,
 * instrument master and combined tax/fee/margin-free premium-risk receipts.
 * Never synthesizes option-chain levels or assumes a short option can fill.
 */
@Component
public class FnoVerifiedNiftyCreditSpreadSourceStore {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final String SQL = """
        SELECT e.provider_code, e.account_scope, e.spread_type, e.index_key,
               e.lower_option_key, e.higher_option_key, e.option_type,
               e.lower_strike, e.higher_strike,
               e.lower_tick_size, e.higher_tick_size,
               e.lower_listed_from, e.higher_listed_from,
               e.expiry, e.lot_size, e.index_price,
               e.index_exchange_at, e.index_first_observed_at,
               e.lower_bid, e.lower_ask, e.lower_bid_depth_lots,
               e.lower_ask_depth_lots, e.lower_oi_lots,
               e.lower_exchange_at, e.lower_first_observed_at,
               e.higher_bid, e.higher_ask, e.higher_bid_depth_lots,
               e.higher_ask_depth_lots, e.higher_oi_lots,
               e.higher_exchange_at, e.higher_first_observed_at,
               e.buy_slippage_bps, e.sell_slippage_bps,
               e.all_in_spread_fees_inr, e.max_each_leg_spread_bps,
               e.minimum_bid_ask_depth_lots, e.minimum_open_interest_lots,
               e.minimum_max_reward_risk_bps, e.research_capital_inr,
               e.max_capital_allocation_bps, e.max_strike_distance_bps,
               e.contract_master_sha256,
               e.available_cash_inr, e.initial_margin_per_spread_lot_inr,
               e.stressed_variation_reserve_inr, e.settlement_assignment_buffer_inr,
               e.stressed_max_loss_per_spread_lot_inr
        FROM fno_verified_nifty_credit_spread_source e
        JOIN golden_data_provider provider ON provider.provider_code = e.provider_code
            AND provider.source_kind = 'EXTERNAL'
        JOIN golden_source_rights_receipt index_rights
            ON index_rights.receipt_id = e.index_rights_receipt_id
            AND index_rights.provider_id = provider.provider_id
            AND index_rights.source_domain = 'nse_nifty_underlying_tick'
            AND index_rights.permitted_use = 'MODEL_INFERENCE'
            AND index_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt lower_rights
            ON lower_rights.receipt_id = e.lower_quote_rights_receipt_id
            AND lower_rights.provider_id = provider.provider_id
            AND lower_rights.source_domain = 'nse_fo_option_chain_depth_oi'
            AND lower_rights.permitted_use = 'MODEL_INFERENCE'
            AND lower_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt higher_rights
            ON higher_rights.receipt_id = e.higher_quote_rights_receipt_id
            AND higher_rights.provider_id = provider.provider_id
            AND higher_rights.source_domain = 'nse_fo_option_chain_depth_oi'
            AND higher_rights.permitted_use = 'MODEL_INFERENCE'
            AND higher_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt master_rights
            ON master_rights.receipt_id = e.master_rights_receipt_id
            AND master_rights.provider_id = provider.provider_id
            AND master_rights.source_domain = 'nse_fo_option_instrument_master'
            AND master_rights.permitted_use = 'MODEL_INFERENCE'
            AND master_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt fee_rights
            ON fee_rights.receipt_id = e.cost_budget_rights_receipt_id
            AND fee_rights.provider_id = provider.provider_id
            AND fee_rights.source_domain = 'nse_fo_option_spread_fees_risk_capital'
            AND fee_rights.permitted_use = 'MODEL_INFERENCE'
            AND fee_rights.receipt_status = 'VERIFIED'
        JOIN golden_source_rights_receipt margin_rights
            ON margin_rights.receipt_id = e.broker_margin_rights_receipt_id
            AND margin_rights.provider_id = provider.provider_id
            AND margin_rights.source_domain = 'nse_fo_broker_credit_spread_margin'
            AND margin_rights.permitted_use = 'MODEL_INFERENCE'
            AND margin_rights.receipt_status = 'VERIFIED'
        WHERE e.account_scope = ?
          AND e.spread_type = ?
          AND e.lower_option_key = ?
          AND e.higher_option_key = ?
          AND e.contract_master_verified
          AND e.both_depths_verified
          AND e.oi_lot_normalization_verified
          AND e.costs_taxes_verified
          AND e.capital_budget_verified
          AND e.broker_margin_reconciled
          AND e.existing_positions_reconciled
          AND e.assignment_delivery_reviewed
          AND e.stressed_loss_reviewed
          AND e.retain_until >= ?
          AND e.captured_at <= ?
          AND e.index_first_observed_at <= ?
          AND e.lower_first_observed_at <= ?
          AND e.higher_first_observed_at <= ?
          AND e.valid_until >= ?
          AND e.index_exchange_at <= ?
          AND e.lower_exchange_at <= ?
          AND e.higher_exchange_at <= ?
          AND index_rights.valid_from <= ?
          AND (index_rights.valid_to IS NULL OR index_rights.valid_to > ?)
          AND index_rights.verified_at <= ? AND index_rights.captured_at <= ?
          AND lower_rights.valid_from <= ?
          AND (lower_rights.valid_to IS NULL OR lower_rights.valid_to > ?)
          AND lower_rights.verified_at <= ? AND lower_rights.captured_at <= ?
          AND higher_rights.valid_from <= ?
          AND (higher_rights.valid_to IS NULL OR higher_rights.valid_to > ?)
          AND higher_rights.verified_at <= ? AND higher_rights.captured_at <= ?
          AND master_rights.valid_from <= ?
          AND (master_rights.valid_to IS NULL OR master_rights.valid_to > ?)
          AND master_rights.verified_at <= ? AND master_rights.captured_at <= ?
          AND fee_rights.valid_from <= ?
          AND (fee_rights.valid_to IS NULL OR fee_rights.valid_to > ?)
          AND fee_rights.verified_at <= ? AND fee_rights.captured_at <= ?
          AND margin_rights.valid_from <= ?
          AND (margin_rights.valid_to IS NULL OR margin_rights.valid_to > ?)
          AND margin_rights.verified_at <= ? AND margin_rights.captured_at <= ?
        ORDER BY e.lower_exchange_at DESC, e.provider_code
        LIMIT 2
        """;

    public record Leg(String key, String kind, LocalDate listedFrom, LocalDate expiry,
                      int lotSize, BigDecimal strike, BigDecimal tickSize,
                      BigDecimal bid, BigDecimal ask, long bidDepthLots,
                      long askDepthLots, long openInterestLots,
                      Instant exchangeAt, Instant firstKnownAt) {}
    public record IndexTick(BigDecimal price, Instant exchangeAt, Instant firstKnownAt) {}
    public record Risk(int buySlippageBps, int sellSlippageBps, BigDecimal feesInr,
                       int maxEachLegSpreadBps, long minDepthLots, long minOiLots,
                       int minRewardRiskBps, BigDecimal capitalInr,
                       int maxAllocationBps, int maxStrikeDistanceBps) {}
    public record Capital(BigDecimal availableCashInr, BigDecimal initialMarginInr,
                          BigDecimal variationMarginInr, BigDecimal settlementBufferInr,
                          BigDecimal stressedMaxLossInr) {}
    public record Proof(String provider, String accountScope, String spreadType,
                        String underlyingKey, Leg lower, Leg higher, IndexTick index,
                        Risk risk, Capital capital, String contractMasterDigest) {}

    private final JdbcTemplate jdbc;

    public FnoVerifiedNiftyCreditSpreadSourceStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public Optional<Proof> oneCurrentSpread(String accountScope, String spreadType,
            String lowerKey, String higherKey, Instant asOf) {
        if (accountScope == null || !accountScope.matches("[A-Za-z0-9_-]{8,80}")
                || !("BULL_PUT".equals(spreadType) || "BEAR_CALL".equals(spreadType))
                || lowerKey == null || higherKey == null
                || !lowerKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,80}")
                || !higherKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,80}")
                || lowerKey.equals(higherKey) || asOf == null) {
            throw new IllegalArgumentException("NIFTY_CREDIT_SPREAD_SOURCE_SCOPE_INVALID");
        }
        Object[] args = new Object[(int) SQL.chars().filter(ch -> ch == '?').count()];
        args[0] = accountScope;
        args[1] = spreadType;
        args[2] = lowerKey;
        args[3] = higherKey;
        args[4] = Date.valueOf(asOf.atZone(IST).toLocalDate());
        Arrays.fill(args, 5, args.length, Timestamp.from(asOf));
        List<Proof> rows = jdbc.query(SQL, (rs, index) -> {
            String kind = rs.getString("option_type");
            LocalDate expiry = rs.getObject("expiry", LocalDate.class);
            int lot = rs.getInt("lot_size");
            Leg lower = new Leg(rs.getString("lower_option_key"), kind,
                rs.getObject("lower_listed_from", LocalDate.class), expiry, lot,
                rs.getBigDecimal("lower_strike"), rs.getBigDecimal("lower_tick_size"),
                rs.getBigDecimal("lower_bid"), rs.getBigDecimal("lower_ask"),
                rs.getLong("lower_bid_depth_lots"), rs.getLong("lower_ask_depth_lots"),
                rs.getLong("lower_oi_lots"),
                rs.getTimestamp("lower_exchange_at").toInstant(),
                rs.getTimestamp("lower_first_observed_at").toInstant());
            Leg higher = new Leg(rs.getString("higher_option_key"), kind,
                rs.getObject("higher_listed_from", LocalDate.class), expiry, lot,
                rs.getBigDecimal("higher_strike"), rs.getBigDecimal("higher_tick_size"),
                rs.getBigDecimal("higher_bid"), rs.getBigDecimal("higher_ask"),
                rs.getLong("higher_bid_depth_lots"), rs.getLong("higher_ask_depth_lots"),
                rs.getLong("higher_oi_lots"),
                rs.getTimestamp("higher_exchange_at").toInstant(),
                rs.getTimestamp("higher_first_observed_at").toInstant());
            return new Proof(rs.getString("provider_code"),
                rs.getString("account_scope"), rs.getString("spread_type"),
                rs.getString("index_key"), lower, higher,
                new IndexTick(rs.getBigDecimal("index_price"),
                    rs.getTimestamp("index_exchange_at").toInstant(),
                    rs.getTimestamp("index_first_observed_at").toInstant()),
                new Risk(rs.getInt("buy_slippage_bps"),
                    rs.getInt("sell_slippage_bps"),
                    rs.getBigDecimal("all_in_spread_fees_inr"),
                    rs.getInt("max_each_leg_spread_bps"),
                    rs.getLong("minimum_bid_ask_depth_lots"),
                    rs.getLong("minimum_open_interest_lots"),
                    rs.getInt("minimum_max_reward_risk_bps"),
                    rs.getBigDecimal("research_capital_inr"),
                    rs.getInt("max_capital_allocation_bps"),
                    rs.getInt("max_strike_distance_bps")),
                new Capital(rs.getBigDecimal("available_cash_inr"),
                    rs.getBigDecimal("initial_margin_per_spread_lot_inr"),
                    rs.getBigDecimal("stressed_variation_reserve_inr"),
                    rs.getBigDecimal("settlement_assignment_buffer_inr"),
                    rs.getBigDecimal("stressed_max_loss_per_spread_lot_inr")),
                rs.getString("contract_master_sha256"));
        }, args);
        if (rows == null || rows.size() != 1) return Optional.empty();
        var p = rows.getFirst();
        if (p == null || p.lower() == null || p.higher() == null || p.index() == null
                || p.risk() == null || p.capital() == null
                || !accountScope.equals(p.accountScope())
                || !spreadType.equals(p.spreadType())
                || !lowerKey.equals(p.lower().key())
                || !higherKey.equals(p.higher().key())
                || !"NSE_INDEX|Nifty 50".equals(p.underlyingKey())
                || !("UPSTOX_ANALYTICS".equals(p.provider())
                    || "ANGEL_ONE_SMARTAPI".equals(p.provider()))
                || p.contractMasterDigest() == null
                || !p.contractMasterDigest().matches("[a-f0-9]{64}")) return Optional.empty();
        return Optional.of(p);
    }

    public int purgeExpired(LocalDate today) {
        return jdbc.update(
            "DELETE FROM fno_verified_nifty_credit_spread_source WHERE retain_until < ?",
            Date.valueOf(Objects.requireNonNull(today)));
    }

    @Scheduled(cron = "0 3 4 * * *", zone = "Asia/Kolkata")
    public void scheduledRetentionPurge() {
        purgeExpired(LocalDate.now(IST));
    }
}
