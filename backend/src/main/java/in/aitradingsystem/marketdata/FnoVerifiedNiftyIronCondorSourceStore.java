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
 * Read-only STR-196 original-time five-quote proof. No records are seeded.
 * Four independently attested source IVs and distinct instrument quote digests
 * are mandatory; no implied volatility is calculated by this store.
 * Expiry payoff can be bounded only when all four actual legs remain intact;
 * this does NOT grant option-writing or real brokerage order rights.
 */
@Component
public final class FnoVerifiedNiftyIronCondorSourceStore {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final String SQL="""
SELECT s.*
   FROM fno_verified_nifty_iron_condor_source s
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
    AND fees_rights.source_domain='nse_fo_four_leg_fees_budget'
   JOIN golden_source_rights_receipt long_put_iv_rights
     ON long_put_iv_rights.receipt_id=s.long_put_iv_rights_receipt_id
    AND long_put_iv_rights.provider_id=provider.provider_id
    AND long_put_iv_rights.source_domain='nse_fo_option_implied_volatility'
   JOIN golden_source_rights_receipt short_put_iv_rights
     ON short_put_iv_rights.receipt_id=s.short_put_iv_rights_receipt_id
    AND short_put_iv_rights.provider_id=provider.provider_id
    AND short_put_iv_rights.source_domain='nse_fo_option_implied_volatility'
   JOIN golden_source_rights_receipt short_call_iv_rights
     ON short_call_iv_rights.receipt_id=s.short_call_iv_rights_receipt_id
    AND short_call_iv_rights.provider_id=provider.provider_id
    AND short_call_iv_rights.source_domain='nse_fo_option_implied_volatility'
   JOIN golden_source_rights_receipt long_call_iv_rights
     ON long_call_iv_rights.receipt_id=s.long_call_iv_rights_receipt_id
    AND long_call_iv_rights.provider_id=provider.provider_id
    AND long_call_iv_rights.source_domain='nse_fo_option_implied_volatility'
  WHERE s.long_put_key=? AND s.short_put_key=?
    AND s.short_call_key=? AND s.long_call_key=?
    AND s.instrument_master_verified
    AND s.nonduplicated_four_leg_fees_verified
    AND s.long_put_iv_source_sha256<>s.short_put_iv_source_sha256
    AND s.long_put_quote_source_sha256<>s.short_put_quote_source_sha256
    AND s.long_put_iv_source_sha256<>s.short_call_iv_source_sha256
    AND s.long_put_quote_source_sha256<>s.short_call_quote_source_sha256
    AND s.long_put_iv_source_sha256<>s.long_call_iv_source_sha256
    AND s.long_put_quote_source_sha256<>s.long_call_quote_source_sha256
    AND s.short_put_iv_source_sha256<>s.short_call_iv_source_sha256
    AND s.short_put_quote_source_sha256<>s.short_call_quote_source_sha256
    AND s.short_put_iv_source_sha256<>s.long_call_iv_source_sha256
    AND s.short_put_quote_source_sha256<>s.long_call_quote_source_sha256
    AND s.short_call_iv_source_sha256<>s.long_call_iv_source_sha256
    AND s.short_call_quote_source_sha256<>s.long_call_quote_source_sha256
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
    AND long_put_iv_rights.permitted_use='MODEL_INFERENCE'
    AND long_put_iv_rights.receipt_status='VERIFIED'
    AND long_put_iv_rights.valid_from<=s.first_known_at
    AND (long_put_iv_rights.valid_to IS NULL OR long_put_iv_rights.valid_to>s.first_known_at)
    AND long_put_iv_rights.verified_at<=s.first_known_at
    AND long_put_iv_rights.captured_at<=s.first_known_at
    AND short_put_iv_rights.permitted_use='MODEL_INFERENCE'
    AND short_put_iv_rights.receipt_status='VERIFIED'
    AND short_put_iv_rights.valid_from<=s.first_known_at
    AND (short_put_iv_rights.valid_to IS NULL OR short_put_iv_rights.valid_to>s.first_known_at)
    AND short_put_iv_rights.verified_at<=s.first_known_at
    AND short_put_iv_rights.captured_at<=s.first_known_at
    AND short_call_iv_rights.permitted_use='MODEL_INFERENCE'
    AND short_call_iv_rights.receipt_status='VERIFIED'
    AND short_call_iv_rights.valid_from<=s.first_known_at
    AND (short_call_iv_rights.valid_to IS NULL OR short_call_iv_rights.valid_to>s.first_known_at)
    AND short_call_iv_rights.verified_at<=s.first_known_at
    AND short_call_iv_rights.captured_at<=s.first_known_at
    AND long_call_iv_rights.permitted_use='MODEL_INFERENCE'
    AND long_call_iv_rights.receipt_status='VERIFIED'
    AND long_call_iv_rights.valid_from<=s.first_known_at
    AND (long_call_iv_rights.valid_to IS NULL OR long_call_iv_rights.valid_to>s.first_known_at)
    AND long_call_iv_rights.verified_at<=s.first_known_at
    AND long_call_iv_rights.captured_at<=s.first_known_at
  ORDER BY s.first_known_at DESC LIMIT 2
        """;
    public record Index(BigDecimal bid,BigDecimal ask,Instant exchangeAt,Instant firstKnownAt) {}
    public record Leg(String key,BigDecimal strike,LocalDate listedFrom,
                      BigDecimal bid,BigDecimal ask,BigDecimal ivPercent,
                      long bidDepthLots,long askDepthLots,long openInterestLots,
                      Instant exchangeAt,Instant firstKnownAt) {}
    public record Budget(int buySlippageBps,int sellSlippageBps,int maxSpreadBps,
                         long minDepthLots,long minOpenInterestLots,
                         int maxWingDistanceBps,int maxIvPercent,
                         BigDecimal fourLegFeesInr,BigDecimal researchCapitalInr,
                         int maxWorstExpiryLossAllocationBps,
                         int minCreditToWorstLossBps,
                         int maxOneSigmaMoveToNearestShortStrikeBps) {}
    public record Proof(String provider,LocalDate expiry,int lotSize,BigDecimal tick,
                        Index index,Leg longPut,Leg shortPut,Leg shortCall,Leg longCall,
                        Budget budget,Instant firstKnownAt) {}
    private final JdbcTemplate jdbc;
    public FnoVerifiedNiftyIronCondorSourceStore(JdbcTemplate jdbc){
        this.jdbc=Objects.requireNonNull(jdbc);
    }
    /** Source ambiguity, missing rights, absent original IV: no candidate. */
    public Optional<Proof> oneCurrent(String lp,String sp,String sc,String lc,Instant at){
        if(at==null || !validKey(lp)||!validKey(sp)||!validKey(sc)||!validKey(lc)
                || java.util.Set.of(lp,sp,sc,lc).size()!=4)
            throw new IllegalArgumentException("IRON_CONDOR_INSTRUMENT_SCOPE_INVALID");
        Object[] args={lp,sp,sc,lc,Timestamp.from(at),Timestamp.from(at),
            Timestamp.from(at),Date.valueOf(at.atZone(IST).toLocalDate()),
            Timestamp.from(at)};
        List<Proof> found=jdbc.query(SQL,(rs,n)->new Proof(
            rs.getString("provider_code"),
            rs.getObject("expiry",LocalDate.class),rs.getInt("lot_size"),
            rs.getBigDecimal("tick"),
            new Index(rs.getBigDecimal("index_bid"),rs.getBigDecimal("index_ask"),
                      rs.getTimestamp("index_exchange_at").toInstant(),
                      rs.getTimestamp("index_first_known_at").toInstant()),
            new Leg(rs.getString("long_put_key"),rs.getBigDecimal("long_put_strike"),
                    rs.getObject("long_put_listed_from",LocalDate.class),
                    rs.getBigDecimal("long_put_bid"),rs.getBigDecimal("long_put_ask"),
                    rs.getBigDecimal("long_put_iv_percent"),rs.getLong("long_put_bid_depth_lots"),
                    rs.getLong("long_put_ask_depth_lots"),rs.getLong("long_put_oi_lots"),
                    rs.getTimestamp("long_put_exchange_at").toInstant(),
                    rs.getTimestamp("long_put_first_known_at").toInstant()),
            new Leg(rs.getString("short_put_key"),rs.getBigDecimal("short_put_strike"),
                    rs.getObject("short_put_listed_from",LocalDate.class),
                    rs.getBigDecimal("short_put_bid"),rs.getBigDecimal("short_put_ask"),
                    rs.getBigDecimal("short_put_iv_percent"),rs.getLong("short_put_bid_depth_lots"),
                    rs.getLong("short_put_ask_depth_lots"),rs.getLong("short_put_oi_lots"),
                    rs.getTimestamp("short_put_exchange_at").toInstant(),
                    rs.getTimestamp("short_put_first_known_at").toInstant()),
            new Leg(rs.getString("short_call_key"),rs.getBigDecimal("short_call_strike"),
                    rs.getObject("short_call_listed_from",LocalDate.class),
                    rs.getBigDecimal("short_call_bid"),rs.getBigDecimal("short_call_ask"),
                    rs.getBigDecimal("short_call_iv_percent"),rs.getLong("short_call_bid_depth_lots"),
                    rs.getLong("short_call_ask_depth_lots"),rs.getLong("short_call_oi_lots"),
                    rs.getTimestamp("short_call_exchange_at").toInstant(),
                    rs.getTimestamp("short_call_first_known_at").toInstant()),
            new Leg(rs.getString("long_call_key"),rs.getBigDecimal("long_call_strike"),
                    rs.getObject("long_call_listed_from",LocalDate.class),
                    rs.getBigDecimal("long_call_bid"),rs.getBigDecimal("long_call_ask"),
                    rs.getBigDecimal("long_call_iv_percent"),rs.getLong("long_call_bid_depth_lots"),
                    rs.getLong("long_call_ask_depth_lots"),rs.getLong("long_call_oi_lots"),
                    rs.getTimestamp("long_call_exchange_at").toInstant(),
                    rs.getTimestamp("long_call_first_known_at").toInstant()),
            new Budget(rs.getInt("buy_slippage_bps"),rs.getInt("sell_slippage_bps"),
                rs.getInt("max_spread_bps"),rs.getLong("min_depth_lots"),
                rs.getLong("min_open_interest_lots"),rs.getInt("max_wing_distance_bps"),
                rs.getInt("max_iv_percent"),rs.getBigDecimal("four_leg_fees_inr"),
                rs.getBigDecimal("research_capital_inr"),
                rs.getInt("max_worst_expiry_loss_allocation_bps"),
                rs.getInt("min_credit_to_worst_loss_bps"),
                rs.getInt("max_one_sigma_to_short_strike_bps")),
            rs.getTimestamp("first_known_at").toInstant()),args);
        if(found==null||found.size()!=1)return Optional.empty();
        Proof p=found.getFirst();
        if(p==null||p.index()==null||p.longPut()==null||p.shortPut()==null
                ||p.shortCall()==null||p.longCall()==null
                ||!lp.equals(p.longPut().key())||!sp.equals(p.shortPut().key())
                ||!sc.equals(p.shortCall().key())||!lc.equals(p.longCall().key())
                ||p.firstKnownAt()==null||p.firstKnownAt().isAfter(at))
            return Optional.empty();
        return Optional.of(p);
    }
    private static boolean validKey(String x){
        return x!=null&&x.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    public int purgeExpired(LocalDate date) {
        return jdbc.update("DELETE FROM fno_verified_nifty_iron_condor_source WHERE retain_until < ?",
            Date.valueOf(Objects.requireNonNull(date)));
    }
    @Scheduled(cron="0 11 4 * * *",zone="Asia/Kolkata")
    public void scheduledPurge(){purgeExpired(LocalDate.now(IST));}
}
