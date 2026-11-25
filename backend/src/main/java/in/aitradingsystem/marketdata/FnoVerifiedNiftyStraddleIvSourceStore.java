package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
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
 * Read-only original market data with two independently captured provider-emitted IVs.
 * A fitted, derived, fallback or guessed IV cannot enter this custody path.
 * All source rows start absent and all research rights must be separately attested.
 */
@Component
public final class FnoVerifiedNiftyStraddleIvSourceStore {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String SQL = """
        SELECT s.provider_code,s.ce_key,s.pe_key,s.strike,s.tick,s.listed_from,
               s.expiry,s.lot_size,s.index_bid,s.index_ask,s.index_exchange_at,
               s.index_first_known_at,s.ce_bid,s.ce_ask,s.ce_iv_percent,
               s.ce_ask_depth_lots,s.ce_open_interest_lots,s.ce_exchange_at,
               s.ce_first_known_at,s.pe_bid,s.pe_ask,s.pe_iv_percent,
               s.pe_ask_depth_lots,s.pe_open_interest_lots,s.pe_exchange_at,
               s.pe_first_known_at,s.buy_slippage_bps,s.max_spread_bps,
               s.min_depth_lots,s.min_oi_lots,s.max_atm_distance_bps,
               s.max_iv_percent,s.all_in_fees_per_lot_inr,s.premium_budget_inr,
               s.max_premium_allocation_bps,s.min_expected_move_over_cost_bps,
               s.first_known_at
          FROM fno_verified_nifty_straddle_iv_source s
          JOIN golden_data_provider provider
            ON provider.provider_code=s.provider_code AND provider.source_kind='EXTERNAL'
          JOIN golden_source_rights_receipt index_rights
            ON index_rights.receipt_id=s.index_rights_receipt_id
           AND index_rights.provider_id=provider.provider_id
           AND index_rights.source_domain='nse_nifty_underlying_tick'
          JOIN golden_source_rights_receipt chain_rights
            ON chain_rights.receipt_id=s.chain_rights_receipt_id
           AND chain_rights.provider_id=provider.provider_id
           AND chain_rights.source_domain='nse_fo_option_chain_depth_oi'
          JOIN golden_source_rights_receipt master_rights
            ON master_rights.receipt_id=s.master_rights_receipt_id
           AND master_rights.provider_id=provider.provider_id
           AND master_rights.source_domain='nse_fo_option_instrument_master'
          JOIN golden_source_rights_receipt ce_iv_rights
            ON ce_iv_rights.receipt_id=s.ce_iv_rights_receipt_id
           AND ce_iv_rights.provider_id=provider.provider_id
           AND ce_iv_rights.source_domain='nse_fo_option_implied_volatility'
          JOIN golden_source_rights_receipt pe_iv_rights
            ON pe_iv_rights.receipt_id=s.pe_iv_rights_receipt_id
           AND pe_iv_rights.provider_id=provider.provider_id
           AND pe_iv_rights.source_domain='nse_fo_option_implied_volatility'
          JOIN golden_source_rights_receipt fees_rights
            ON fees_rights.receipt_id=s.fees_rights_receipt_id
           AND fees_rights.provider_id=provider.provider_id
           AND fees_rights.source_domain='nse_fo_two_leg_fees_budget'
         WHERE s.ce_key=? AND s.pe_key=? AND s.instrument_master_verified
           AND s.original_iv_ce_verified AND s.original_iv_pe_verified
           AND s.ce_iv_source_sha256<>s.pe_iv_source_sha256
           AND s.first_known_at<=? AND s.valid_until>=?
           AND s.captured_at<=? AND s.retain_until>=?
           AND s.first_known_at>=?::timestamptz-INTERVAL '180 seconds'
           AND index_rights.permitted_use='MODEL_INFERENCE'
           AND chain_rights.permitted_use='MODEL_INFERENCE'
           AND master_rights.permitted_use='MODEL_INFERENCE'
           AND ce_iv_rights.permitted_use='MODEL_INFERENCE'
           AND pe_iv_rights.permitted_use='MODEL_INFERENCE'
           AND fees_rights.permitted_use='MODEL_INFERENCE'
           AND index_rights.receipt_status='VERIFIED'
           AND chain_rights.receipt_status='VERIFIED'
           AND master_rights.receipt_status='VERIFIED'
           AND ce_iv_rights.receipt_status='VERIFIED'
           AND pe_iv_rights.receipt_status='VERIFIED'
           AND fees_rights.receipt_status='VERIFIED'
           AND index_rights.valid_from<=s.first_known_at
           AND chain_rights.valid_from<=s.first_known_at
           AND master_rights.valid_from<=s.first_known_at
           AND ce_iv_rights.valid_from<=s.first_known_at
           AND pe_iv_rights.valid_from<=s.first_known_at
           AND fees_rights.valid_from<=s.first_known_at
           AND (index_rights.valid_to IS NULL OR index_rights.valid_to>s.first_known_at)
           AND (chain_rights.valid_to IS NULL OR chain_rights.valid_to>s.first_known_at)
           AND (master_rights.valid_to IS NULL OR master_rights.valid_to>s.first_known_at)
           AND (ce_iv_rights.valid_to IS NULL OR ce_iv_rights.valid_to>s.first_known_at)
           AND (pe_iv_rights.valid_to IS NULL OR pe_iv_rights.valid_to>s.first_known_at)
           AND (fees_rights.valid_to IS NULL OR fees_rights.valid_to>s.first_known_at)
           AND index_rights.verified_at<=s.first_known_at
           AND chain_rights.verified_at<=s.first_known_at
           AND master_rights.verified_at<=s.first_known_at
           AND ce_iv_rights.verified_at<=s.first_known_at
           AND pe_iv_rights.verified_at<=s.first_known_at
           AND fees_rights.verified_at<=s.first_known_at
           AND index_rights.captured_at<=s.first_known_at
           AND chain_rights.captured_at<=s.first_known_at
           AND master_rights.captured_at<=s.first_known_at
           AND ce_iv_rights.captured_at<=s.first_known_at
           AND pe_iv_rights.captured_at<=s.first_known_at
           AND fees_rights.captured_at<=s.first_known_at
         ORDER BY s.first_known_at DESC LIMIT 2
        """;

    public record Leg(String key,BigDecimal bid,BigDecimal ask,BigDecimal ivPercent,
                      long askDepthLots,long openInterestLots,
                      Instant exchangeAt,Instant firstKnownAt) {}
    public record Index(BigDecimal bid,BigDecimal ask,Instant exchangeAt,Instant firstKnownAt) {}
    public record Budget(int buySlippageBps,int maxSpreadBps,long minDepthLots,long minOiLots,
                         int maxAtmDistanceBps,int maxIvPercent,BigDecimal allInFeesInr,
                         BigDecimal premiumBudgetInr,int maxPremiumAllocationBps,
                         int minExpectedMoveOverCostBps) {}
    public record Proof(String provider,BigDecimal strike,BigDecimal tick,
                        LocalDate listedFrom,LocalDate expiry,int lotSize,
                        Index index,Leg call,Leg put,Budget budget,Instant firstKnownAt) {}
    private final JdbcTemplate jdbc;
    public FnoVerifiedNiftyStraddleIvSourceStore(JdbcTemplate jdbc) {
        this.jdbc=Objects.requireNonNull(jdbc);
    }
    /** Ambiguous overlapping original observations fail closed. */
    public Optional<Proof> oneCurrent(String ceKey,String peKey,Instant asOf) {
        if (!validKey(ceKey) || !validKey(peKey) || ceKey.equals(peKey) || asOf==null)
            throw new IllegalArgumentException("STRADDLE_IV_SOURCE_SCOPE_INVALID");
        Object[] params={ceKey,peKey,Timestamp.from(asOf),Timestamp.from(asOf),
            Timestamp.from(asOf),Date.valueOf(asOf.atZone(IST).toLocalDate()),
            Timestamp.from(asOf)};
        List<Proof> rows=jdbc.query(SQL,(rs,i)->new Proof(
            rs.getString("provider_code"),rs.getBigDecimal("strike"),rs.getBigDecimal("tick"),
            rs.getObject("listed_from",LocalDate.class),
            rs.getObject("expiry",LocalDate.class),rs.getInt("lot_size"),
            new Index(rs.getBigDecimal("index_bid"),rs.getBigDecimal("index_ask"),
                rs.getTimestamp("index_exchange_at").toInstant(),
                rs.getTimestamp("index_first_known_at").toInstant()),
            new Leg(rs.getString("ce_key"),rs.getBigDecimal("ce_bid"),
                rs.getBigDecimal("ce_ask"),rs.getBigDecimal("ce_iv_percent"),
                rs.getLong("ce_ask_depth_lots"),rs.getLong("ce_open_interest_lots"),
                rs.getTimestamp("ce_exchange_at").toInstant(),
                rs.getTimestamp("ce_first_known_at").toInstant()),
            new Leg(rs.getString("pe_key"),rs.getBigDecimal("pe_bid"),
                rs.getBigDecimal("pe_ask"),rs.getBigDecimal("pe_iv_percent"),
                rs.getLong("pe_ask_depth_lots"),rs.getLong("pe_open_interest_lots"),
                rs.getTimestamp("pe_exchange_at").toInstant(),
                rs.getTimestamp("pe_first_known_at").toInstant()),
            new Budget(rs.getInt("buy_slippage_bps"),rs.getInt("max_spread_bps"),
                rs.getLong("min_depth_lots"),rs.getLong("min_oi_lots"),
                rs.getInt("max_atm_distance_bps"),rs.getInt("max_iv_percent"),
                rs.getBigDecimal("all_in_fees_per_lot_inr"),
                rs.getBigDecimal("premium_budget_inr"),
                rs.getInt("max_premium_allocation_bps"),
                rs.getInt("min_expected_move_over_cost_bps")),
            rs.getTimestamp("first_known_at").toInstant()),params);
        if(rows==null || rows.size()!=1)return Optional.empty();
        Proof p=rows.getFirst();
        if(p==null || !validKey(p.call().key()) || !validKey(p.put().key())
                || !ceKey.equals(p.call().key()) || !peKey.equals(p.put().key())
                || p.firstKnownAt()==null || p.firstKnownAt().isAfter(asOf))
            return Optional.empty();
        return Optional.of(p);
    }
    private static boolean validKey(String key) {
        return key!=null && key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    public int purgeExpired(LocalDate date) {
        return jdbc.update("DELETE FROM fno_verified_nifty_straddle_iv_source WHERE retain_until < ?",
            Date.valueOf(Objects.requireNonNull(date)));
    }
    @Scheduled(cron="0 7 4 * * *",zone="Asia/Kolkata")
    public void scheduledRetentionPurge() {purgeExpired(LocalDate.now(IST));}
}
