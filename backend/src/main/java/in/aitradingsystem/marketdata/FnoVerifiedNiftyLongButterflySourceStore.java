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
 * Read-only original-time 1x2x1 CALL butterfly custody with three separate listed CE keys.
 * All three option source IVs must be genuinely provider-emitted and separately attested.
 * The 2x center short BID depth is mandatory; mid/ask does not substitute for sale.
 * No research results are broker orders and all original source rows are absent by default.
 */
@Component
public final class FnoVerifiedNiftyLongButterflySourceStore {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final String SQL="""
SELECT s.*
  FROM fno_verified_nifty_long_butterfly_source s
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
  JOIN golden_source_rights_receipt fees_rights
    ON fees_rights.receipt_id=s.fees_rights_receipt_id
   AND fees_rights.provider_id=provider.provider_id
   AND fees_rights.source_domain='nse_fo_four_contract_fees_budget'
  JOIN golden_source_rights_receipt lower_iv_rights
    ON lower_iv_rights.receipt_id=s.lower_iv_rights_receipt_id
   AND lower_iv_rights.provider_id=provider.provider_id
   AND lower_iv_rights.source_domain='nse_fo_option_implied_volatility'
  JOIN golden_source_rights_receipt center_iv_rights
    ON center_iv_rights.receipt_id=s.center_iv_rights_receipt_id
   AND center_iv_rights.provider_id=provider.provider_id
   AND center_iv_rights.source_domain='nse_fo_option_implied_volatility'
  JOIN golden_source_rights_receipt upper_iv_rights
    ON upper_iv_rights.receipt_id=s.upper_iv_rights_receipt_id
   AND upper_iv_rights.provider_id=provider.provider_id
   AND upper_iv_rights.source_domain='nse_fo_option_implied_volatility'
 WHERE s.lower_key=? AND s.center_key=? AND s.upper_key=?
   AND s.instrument_master_verified AND s.four_contract_fees_verified
   AND s.two_center_short_lots_verified
   AND s.center_bid_depth_lots>=2*s.min_depth_lots
   AND s.lower_ask_depth_lots>=s.min_depth_lots
   AND s.upper_ask_depth_lots>=s.min_depth_lots
   AND s.lower_original_iv_verified AND s.center_original_iv_verified AND s.upper_original_iv_verified
   AND s.lower_iv_source_sha256<>s.center_iv_source_sha256 AND
   s.lower_quote_source_sha256<>s.center_quote_source_sha256 AND
   s.lower_iv_source_sha256<>s.upper_iv_source_sha256 AND
   s.lower_quote_source_sha256<>s.upper_quote_source_sha256 AND
   s.center_iv_source_sha256<>s.upper_iv_source_sha256 AND
   s.center_quote_source_sha256<>s.upper_quote_source_sha256
   AND s.first_known_at<=? AND s.valid_until>=?
   AND s.captured_at<=? AND s.retain_until>=?
   AND s.first_known_at>=?::timestamptz-INTERVAL '180 seconds'
   AND index_rights.permitted_use='MODEL_INFERENCE'
   AND index_rights.receipt_status='VERIFIED'
   AND index_rights.valid_from<=s.first_known_at
   AND (index_rights.valid_to IS NULL OR index_rights.valid_to>s.first_known_at)
   AND index_rights.verified_at<=s.first_known_at
   AND index_rights.captured_at<=s.first_known_at
   AND chain_rights.permitted_use='MODEL_INFERENCE'
   AND chain_rights.receipt_status='VERIFIED'
   AND chain_rights.valid_from<=s.first_known_at
   AND (chain_rights.valid_to IS NULL OR chain_rights.valid_to>s.first_known_at)
   AND chain_rights.verified_at<=s.first_known_at
   AND chain_rights.captured_at<=s.first_known_at
   AND master_rights.permitted_use='MODEL_INFERENCE'
   AND master_rights.receipt_status='VERIFIED'
   AND master_rights.valid_from<=s.first_known_at
   AND (master_rights.valid_to IS NULL OR master_rights.valid_to>s.first_known_at)
   AND master_rights.verified_at<=s.first_known_at
   AND master_rights.captured_at<=s.first_known_at
   AND fees_rights.permitted_use='MODEL_INFERENCE'
   AND fees_rights.receipt_status='VERIFIED'
   AND fees_rights.valid_from<=s.first_known_at
   AND (fees_rights.valid_to IS NULL OR fees_rights.valid_to>s.first_known_at)
   AND fees_rights.verified_at<=s.first_known_at
   AND fees_rights.captured_at<=s.first_known_at
   AND lower_iv_rights.permitted_use='MODEL_INFERENCE'
   AND lower_iv_rights.receipt_status='VERIFIED'
   AND lower_iv_rights.valid_from<=s.first_known_at
   AND (lower_iv_rights.valid_to IS NULL OR lower_iv_rights.valid_to>s.first_known_at)
   AND lower_iv_rights.verified_at<=s.first_known_at
   AND lower_iv_rights.captured_at<=s.first_known_at
   AND center_iv_rights.permitted_use='MODEL_INFERENCE'
   AND center_iv_rights.receipt_status='VERIFIED'
   AND center_iv_rights.valid_from<=s.first_known_at
   AND (center_iv_rights.valid_to IS NULL OR center_iv_rights.valid_to>s.first_known_at)
   AND center_iv_rights.verified_at<=s.first_known_at
   AND center_iv_rights.captured_at<=s.first_known_at
   AND upper_iv_rights.permitted_use='MODEL_INFERENCE'
   AND upper_iv_rights.receipt_status='VERIFIED'
   AND upper_iv_rights.valid_from<=s.first_known_at
   AND (upper_iv_rights.valid_to IS NULL OR upper_iv_rights.valid_to>s.first_known_at)
   AND upper_iv_rights.verified_at<=s.first_known_at
   AND upper_iv_rights.captured_at<=s.first_known_at
 ORDER BY s.first_known_at DESC LIMIT 2
        """;

    public record Index(BigDecimal bid,BigDecimal ask,Instant exchangeAt,Instant firstKnownAt) {}
    public record Leg(String key,BigDecimal strike,LocalDate listedFrom,
                      BigDecimal bid,BigDecimal ask,BigDecimal ivPercent,
                      long bidDepthLots,long askDepthLots,long oiLots,
                      Instant exchangeAt,Instant firstKnownAt) {}
    public record Budget(int buySlippageBps,int sellSlippageBps,int maxSpreadBps,
                         long minDepthLots,long minOiLots,int maxWingDistanceBps,
                         int maxAtmDistanceBps,int maxIvPercent,
                         BigDecimal fourContractFeesInr,BigDecimal researchCapitalInr,
                         int maxDebitAllocationBps,int minRewardToRiskBps,
                         int maxIvMoveToWingBps) {}
    public record Proof(String provider,LocalDate expiry,int lotSize,BigDecimal tick,
                        Index index,Leg lower,Leg center,Leg upper,Budget budget,
                        Instant firstKnownAt) {}

    private final JdbcTemplate jdbc;
    public FnoVerifiedNiftyLongButterflySourceStore(JdbcTemplate jdbc){
        this.jdbc=Objects.requireNonNull(jdbc);
    }
    public Optional<Proof> oneCurrent(String lower,String center,String upper,Instant at){
        if(at==null||!validKey(lower)||!validKey(center)||!validKey(upper)
                ||java.util.Set.of(lower,center,upper).size()!=3)
            throw new IllegalArgumentException("LONG_BUTTERFLY_KEYS_UNVERIFIED");
        Object[] args={lower,center,upper,Timestamp.from(at),Timestamp.from(at),
            Timestamp.from(at),Date.valueOf(at.atZone(IST).toLocalDate()),
            Timestamp.from(at)};
        List<Proof> rows=jdbc.query(SQL,(rs,n)->new Proof(
            rs.getString("provider_code"),
            rs.getObject("expiry",LocalDate.class),rs.getInt("lot_size"),
            rs.getBigDecimal("tick"),
            new Index(rs.getBigDecimal("index_bid"),rs.getBigDecimal("index_ask"),
                rs.getTimestamp("index_exchange_at").toInstant(),
                rs.getTimestamp("index_first_known_at").toInstant()),
            new Leg(rs.getString("lower_key"),rs.getBigDecimal("lower_strike"),
                rs.getObject("lower_listed_from",LocalDate.class),
                rs.getBigDecimal("lower_bid"),rs.getBigDecimal("lower_ask"),
                rs.getBigDecimal("lower_iv_percent"),
                rs.getLong("lower_bid_depth_lots"),rs.getLong("lower_ask_depth_lots"),
                rs.getLong("lower_oi_lots"),
                rs.getTimestamp("lower_exchange_at").toInstant(),
                rs.getTimestamp("lower_first_known_at").toInstant()),
            new Leg(rs.getString("center_key"),rs.getBigDecimal("center_strike"),
                rs.getObject("center_listed_from",LocalDate.class),
                rs.getBigDecimal("center_bid"),rs.getBigDecimal("center_ask"),
                rs.getBigDecimal("center_iv_percent"),
                rs.getLong("center_bid_depth_lots"),rs.getLong("center_ask_depth_lots"),
                rs.getLong("center_oi_lots"),
                rs.getTimestamp("center_exchange_at").toInstant(),
                rs.getTimestamp("center_first_known_at").toInstant()),
            new Leg(rs.getString("upper_key"),rs.getBigDecimal("upper_strike"),
                rs.getObject("upper_listed_from",LocalDate.class),
                rs.getBigDecimal("upper_bid"),rs.getBigDecimal("upper_ask"),
                rs.getBigDecimal("upper_iv_percent"),
                rs.getLong("upper_bid_depth_lots"),rs.getLong("upper_ask_depth_lots"),
                rs.getLong("upper_oi_lots"),
                rs.getTimestamp("upper_exchange_at").toInstant(),
                rs.getTimestamp("upper_first_known_at").toInstant()),
            new Budget(rs.getInt("buy_slippage_bps"),rs.getInt("sell_slippage_bps"),
                rs.getInt("max_spread_bps"),rs.getLong("min_depth_lots"),
                rs.getLong("min_open_interest_lots"),
                rs.getInt("max_wing_distance_bps"),
                rs.getInt("max_center_atm_distance_bps"),
                rs.getInt("max_iv_percent"),
                rs.getBigDecimal("four_contract_fees_inr"),
                rs.getBigDecimal("research_capital_inr"),
                rs.getInt("max_debit_allocation_bps"),
                rs.getInt("min_reward_to_risk_bps"),
                rs.getInt("max_iv_move_to_wing_bps")),
            rs.getTimestamp("first_known_at").toInstant()),args);
        if(rows==null||rows.size()!=1)return Optional.empty();
        Proof p=rows.getFirst();
        if(p==null||p.index()==null||p.lower()==null||p.center()==null||p.upper()==null
            ||!lower.equals(p.lower().key())||!center.equals(p.center().key())
            ||!upper.equals(p.upper().key())
            ||p.firstKnownAt()==null||p.firstKnownAt().isAfter(at))
            return Optional.empty();
        return Optional.of(p);
    }
    private static boolean validKey(String x){
        return x!=null&&x.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    public int purgeExpired(LocalDate today){
        return jdbc.update("DELETE FROM fno_verified_nifty_long_butterfly_source WHERE retain_until < ?",
            Date.valueOf(Objects.requireNonNull(today)));
    }
    @Scheduled(cron="0 15 4 * * *",zone="Asia/Kolkata")
    public void scheduledPurge(){purgeExpired(LocalDate.now(IST));}
}
