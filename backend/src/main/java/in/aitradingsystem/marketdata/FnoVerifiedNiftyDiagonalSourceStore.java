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

/** Original-time proof for different OTM-short and ITM-long strikes AND expiries. Never infers far-leg
 * near-expiry mark, IV, executable margin, or a common-expiry maximum loss. */
@Component
public final class FnoVerifiedNiftyDiagonalSourceStore {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final String SQL="""
SELECT s.* FROM fno_verified_nifty_diagonal_source s
 JOIN golden_data_provider provider ON provider.provider_code=s.provider_code AND provider.source_kind='EXTERNAL'
 JOIN golden_source_rights_receipt index_rights ON index_rights.receipt_id=s.index_rights_receipt_id AND index_rights.provider_id=provider.provider_id AND index_rights.source_domain='nse_nifty_underlying_tick'
 JOIN golden_source_rights_receipt chain_rights ON chain_rights.receipt_id=s.chain_rights_receipt_id AND chain_rights.provider_id=provider.provider_id AND chain_rights.source_domain='nse_fo_option_chain_depth_oi'
 JOIN golden_source_rights_receipt master_rights ON master_rights.receipt_id=s.master_rights_receipt_id AND master_rights.provider_id=provider.provider_id AND master_rights.source_domain='nse_fo_option_instrument_master'
 JOIN golden_source_rights_receipt fees_rights ON fees_rights.receipt_id=s.fees_rights_receipt_id AND fees_rights.provider_id=provider.provider_id AND fees_rights.source_domain='nse_fo_two_leg_fees_budget'
 JOIN golden_source_rights_receipt near_iv_rights ON near_iv_rights.receipt_id=s.near_iv_rights_receipt_id AND near_iv_rights.provider_id=provider.provider_id AND near_iv_rights.source_domain='nse_fo_option_implied_volatility'
 JOIN golden_source_rights_receipt far_iv_rights ON far_iv_rights.receipt_id=s.far_iv_rights_receipt_id AND far_iv_rights.provider_id=provider.provider_id AND far_iv_rights.source_domain='nse_fo_option_implied_volatility'
 WHERE s.near_key=? AND s.far_key=? AND s.option_type=?
 AND s.instrument_master_verified AND s.original_two_leg_fees_verified
 AND s.near_strike<>s.far_strike
 AND ((s.option_type='CE' AND s.far_strike<s.index_bid AND s.index_bid<s.near_strike)
   OR (s.option_type='PE' AND s.near_strike<s.index_bid AND s.index_bid<s.far_strike))
 AND s.near_original_iv_verified AND s.far_original_iv_verified
 AND s.near_iv_source_sha256<>s.far_iv_source_sha256
 AND s.near_quote_source_sha256<>s.far_quote_source_sha256
 AND s.near_bid_depth_lots>=s.min_depth_lots AND s.far_ask_depth_lots>=s.min_depth_lots
 AND s.near_oi_lots>=s.min_oi_lots AND s.far_oi_lots>=s.min_oi_lots
 AND s.first_known_at<=? AND s.valid_until>=? AND s.captured_at<=?
 AND s.retain_until>=? AND s.first_known_at>=?::timestamptz-INTERVAL '180 seconds'
 AND index_rights.permitted_use='MODEL_INFERENCE' AND index_rights.receipt_status='VERIFIED'
 AND index_rights.valid_from<=s.first_known_at AND (index_rights.valid_to IS NULL OR index_rights.valid_to>s.first_known_at)
 AND index_rights.verified_at<=s.first_known_at AND index_rights.captured_at<=s.first_known_at
 AND chain_rights.permitted_use='MODEL_INFERENCE' AND chain_rights.receipt_status='VERIFIED'
 AND chain_rights.valid_from<=s.first_known_at AND (chain_rights.valid_to IS NULL OR chain_rights.valid_to>s.first_known_at)
 AND chain_rights.verified_at<=s.first_known_at AND chain_rights.captured_at<=s.first_known_at
 AND master_rights.permitted_use='MODEL_INFERENCE' AND master_rights.receipt_status='VERIFIED'
 AND master_rights.valid_from<=s.first_known_at AND (master_rights.valid_to IS NULL OR master_rights.valid_to>s.first_known_at)
 AND master_rights.verified_at<=s.first_known_at AND master_rights.captured_at<=s.first_known_at
 AND fees_rights.permitted_use='MODEL_INFERENCE' AND fees_rights.receipt_status='VERIFIED'
 AND fees_rights.valid_from<=s.first_known_at AND (fees_rights.valid_to IS NULL OR fees_rights.valid_to>s.first_known_at)
 AND fees_rights.verified_at<=s.first_known_at AND fees_rights.captured_at<=s.first_known_at
 AND near_iv_rights.permitted_use='MODEL_INFERENCE' AND near_iv_rights.receipt_status='VERIFIED'
 AND near_iv_rights.valid_from<=s.first_known_at AND (near_iv_rights.valid_to IS NULL OR near_iv_rights.valid_to>s.first_known_at)
 AND near_iv_rights.verified_at<=s.first_known_at AND near_iv_rights.captured_at<=s.first_known_at
 AND far_iv_rights.permitted_use='MODEL_INFERENCE' AND far_iv_rights.receipt_status='VERIFIED'
 AND far_iv_rights.valid_from<=s.first_known_at AND (far_iv_rights.valid_to IS NULL OR far_iv_rights.valid_to>s.first_known_at)
 AND far_iv_rights.verified_at<=s.first_known_at AND far_iv_rights.captured_at<=s.first_known_at
 ORDER BY s.first_known_at DESC LIMIT 2
        """;
    public record Index(BigDecimal bid,BigDecimal ask,Instant exchangeAt,Instant firstKnownAt) {}
    public record Leg(String key,LocalDate expiry,LocalDate listedFrom,
        BigDecimal bid,BigDecimal ask,BigDecimal ivPercent,
        long bidDepthLots,long askDepthLots,long oiLots,
        Instant exchangeAt,Instant firstKnownAt) {}
    public record Budget(int buySlippageBps,int sellSlippageBps,
        int maxSpreadBps,long minDepthLots,long minOiLots,
        int maxStrikeDistanceBps,int maxIvPercent,
        BigDecimal twoLegFeesInr,BigDecimal researchCapitalInr,
        int maxNetDebitAllocationBps,int minNearFarIvRatioBps,
        int minNearPremiumFarPremiumBps,int maxNearOneSigmaBps) {}
    public record Proof(String provider,String optionType,BigDecimal nearStrike,
        BigDecimal farStrike,BigDecimal tick,int lotSize,Index index,Leg near,Leg far,Budget budget,
        Instant firstKnownAt) {}

    private final JdbcTemplate jdbc;
    public FnoVerifiedNiftyDiagonalSourceStore(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    public Optional<Proof> oneCurrent(String near,String far,String type,Instant at){
        if(at==null||!validKey(near)||!validKey(far)||near.equals(far)
            ||!("CE".equals(type)||"PE".equals(type)))
            throw new IllegalArgumentException("DIAGONAL_SOURCE_CONTRACT_SCOPE_INVALID");
        Object[] args={near,far,type,Timestamp.from(at),Timestamp.from(at),
            Timestamp.from(at),Date.valueOf(at.atZone(IST).toLocalDate()),Timestamp.from(at)};
        List<Proof> found=jdbc.query(SQL,(rs,n)->new Proof(
            rs.getString("provider_code"),rs.getString("option_type"),
            rs.getBigDecimal("near_strike"),rs.getBigDecimal("far_strike"),rs.getBigDecimal("tick"),rs.getInt("lot_size"),
            new Index(rs.getBigDecimal("index_bid"),rs.getBigDecimal("index_ask"),
                rs.getTimestamp("index_exchange_at").toInstant(),
                rs.getTimestamp("index_first_known_at").toInstant()),
            new Leg(rs.getString("near_key"),rs.getObject("near_expiry",LocalDate.class),
                rs.getObject("near_listed_from",LocalDate.class),rs.getBigDecimal("near_bid"),
                rs.getBigDecimal("near_ask"),rs.getBigDecimal("near_iv_percent"),
                rs.getLong("near_bid_depth_lots"),rs.getLong("near_ask_depth_lots"),
                rs.getLong("near_oi_lots"),rs.getTimestamp("near_exchange_at").toInstant(),
                rs.getTimestamp("near_first_known_at").toInstant()),
            new Leg(rs.getString("far_key"),rs.getObject("far_expiry",LocalDate.class),
                rs.getObject("far_listed_from",LocalDate.class),rs.getBigDecimal("far_bid"),
                rs.getBigDecimal("far_ask"),rs.getBigDecimal("far_iv_percent"),
                rs.getLong("far_bid_depth_lots"),rs.getLong("far_ask_depth_lots"),
                rs.getLong("far_oi_lots"),rs.getTimestamp("far_exchange_at").toInstant(),
                rs.getTimestamp("far_first_known_at").toInstant()),
            new Budget(rs.getInt("buy_slippage_bps"),rs.getInt("sell_slippage_bps"),
                rs.getInt("max_spread_bps"),rs.getLong("min_depth_lots"),
                rs.getLong("min_oi_lots"),rs.getInt("max_strike_distance_bps"),
                rs.getInt("max_iv_percent"),rs.getBigDecimal("two_leg_fees_inr"),
                rs.getBigDecimal("research_capital_inr"),rs.getInt("max_net_debit_allocation_bps"),
                rs.getInt("min_near_far_iv_ratio_bps"),rs.getInt("min_near_premium_far_premium_bps"),
                rs.getInt("max_near_one_sigma_bps")),
            rs.getTimestamp("first_known_at").toInstant()),args);
        if(found==null||found.size()!=1)return Optional.empty();
        var p=found.getFirst();
        if(p==null||p.near()==null||p.far()==null||p.index()==null
            ||!near.equals(p.near().key())||!far.equals(p.far().key())
            ||!type.equals(p.optionType())||p.firstKnownAt()==null||p.firstKnownAt().isAfter(at))
            return Optional.empty();
        return Optional.of(p);
    }
    private static boolean validKey(String key){
        return key!=null&&key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    public int purgeExpired(LocalDate date){
        return jdbc.update("DELETE FROM fno_verified_nifty_diagonal_source WHERE retain_until < ?",
            Date.valueOf(Objects.requireNonNull(date)));
    }
    @Scheduled(cron="0 19 4 * * *",zone="Asia/Kolkata")
    public void scheduledPurge(){purgeExpired(LocalDate.now(IST));}
}
