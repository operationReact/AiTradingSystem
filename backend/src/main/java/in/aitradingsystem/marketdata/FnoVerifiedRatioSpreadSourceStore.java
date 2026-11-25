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

/** Read-only source-attested NIFTY option 1x2 ratio evidence. One common
 * original-time source window, broker margin stress, full two-short-lot depth,
 * independently verified combined fees. Never infers a bounded call tail. */
@Component
public final class FnoVerifiedRatioSpreadSourceStore {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final String SQL="""
        SELECT s.provider_code,s.account_scope,s.ratio_side,s.index_key,s.long_key,s.short_key,
               s.long_strike,s.short_strike,s.long_tick,s.short_tick,
               s.long_listed_from,s.short_listed_from,s.expiry,s.lot_size,
               s.index_bid,s.index_ask,s.index_exchange_at,s.index_first_known_at,
               s.long_bid,s.long_ask,s.long_bid_depth_lots,s.long_ask_depth_lots,
               s.long_oi_lots,s.long_exchange_at,s.long_first_known_at,
               s.short_bid,s.short_ask,s.short_bid_depth_lots,s.short_ask_depth_lots,
               s.short_oi_lots,s.short_exchange_at,s.short_first_known_at,
               s.buy_slippage_bps,s.sell_slippage_bps,
               s.max_option_spread_bps,s.min_bid_ask_depth_lots,
               s.min_oi_lots,s.max_strike_distance_bps,s.total_three_leg_fees_inr,
               s.research_capital_inr,s.max_loss_allocation_bps,
               s.stress_underlying_price,s.min_peak_reward_bps,
               s.broker_margin_reserve_inr,s.contract_source_sha256,
               s.first_known_at
          FROM fno_verified_nifty_ratio_spread_source s
          JOIN golden_data_provider provider ON provider.provider_code=s.provider_code
           AND provider.source_kind='EXTERNAL'
          JOIN golden_source_rights_receipt index_rights
            ON index_rights.receipt_id=s.index_rights_receipt_id
           AND index_rights.provider_id=provider.provider_id
           AND index_rights.source_domain='nse_nifty_underlying_tick'
           AND index_rights.permitted_use='MODEL_INFERENCE'
           AND index_rights.receipt_status='VERIFIED'
          JOIN golden_source_rights_receipt long_rights
            ON long_rights.receipt_id=s.long_rights_receipt_id
           AND long_rights.provider_id=provider.provider_id
           AND long_rights.source_domain='nse_fo_option_chain_depth_oi'
           AND long_rights.permitted_use='MODEL_INFERENCE'
           AND long_rights.receipt_status='VERIFIED'
          JOIN golden_source_rights_receipt short_rights
            ON short_rights.receipt_id=s.short_rights_receipt_id
           AND short_rights.provider_id=provider.provider_id
           AND short_rights.source_domain='nse_fo_option_chain_depth_oi'
           AND short_rights.permitted_use='MODEL_INFERENCE'
           AND short_rights.receipt_status='VERIFIED'
          JOIN golden_source_rights_receipt master_rights
            ON master_rights.receipt_id=s.master_rights_receipt_id
           AND master_rights.provider_id=provider.provider_id
           AND master_rights.source_domain='nse_fo_option_instrument_master'
           AND master_rights.permitted_use='MODEL_INFERENCE'
           AND master_rights.receipt_status='VERIFIED'
          JOIN golden_source_rights_receipt cost_rights
            ON cost_rights.receipt_id=s.margin_cost_rights_receipt_id
           AND cost_rights.provider_id=provider.provider_id
           AND cost_rights.source_domain='nse_fo_ratio_3leg_fees_margin'
           AND cost_rights.permitted_use='MODEL_INFERENCE'
           AND cost_rights.receipt_status='VERIFIED'
         WHERE s.account_scope=? AND s.ratio_side=? AND s.long_key=? AND s.short_key=?
           AND s.independently_reconciled_margin AND s.true_two_short_lots_depth
           AND s.three_leg_fees_not_double_counted
           AND s.source_instrument_master_verified AND s.stress_tail_risk_reviewed
           AND s.first_known_at<=? AND s.captured_at<=?
           AND s.valid_until>=? AND s.retain_until>=?
           AND s.index_first_known_at<=? AND s.long_first_known_at<=?
           AND s.short_first_known_at<=?
           AND index_rights.valid_from<=?
           AND (index_rights.valid_to IS NULL OR index_rights.valid_to>?)
           AND index_rights.verified_at<=? AND index_rights.captured_at<=?
           AND long_rights.valid_from<=?
           AND (long_rights.valid_to IS NULL OR long_rights.valid_to>?)
           AND long_rights.verified_at<=? AND long_rights.captured_at<=?
           AND short_rights.valid_from<=?
           AND (short_rights.valid_to IS NULL OR short_rights.valid_to>?)
           AND short_rights.verified_at<=? AND short_rights.captured_at<=?
           AND master_rights.valid_from<=?
           AND (master_rights.valid_to IS NULL OR master_rights.valid_to>?)
           AND master_rights.verified_at<=? AND master_rights.captured_at<=?
           AND cost_rights.valid_from<=?
           AND (cost_rights.valid_to IS NULL OR cost_rights.valid_to>?)
           AND cost_rights.verified_at<=? AND cost_rights.captured_at<=?
         ORDER BY s.first_known_at DESC LIMIT 2
        """;
    public record OptionLeg(String key,BigDecimal strike,BigDecimal tick,
                            LocalDate listedFrom,LocalDate expiry,int lotSize,
                            BigDecimal bid,BigDecimal ask,long bidDepthLots,
                            long askDepthLots,long oiLots,Instant exchangeAt,
                            Instant firstKnownAt){}
    public record IndexQuote(BigDecimal bid,BigDecimal ask,
                             Instant exchangeAt,Instant firstKnownAt){}
    public record Risk(int buySlippageBps,int sellSlippageBps,
                       int maxOptionSpreadBps,long minDepthLots,long minOiLots,
                       int maxStrikeDistanceBps,BigDecimal threeLegFeesInr,
                       BigDecimal capitalInr,int maxLossAllocationBps,
                       BigDecimal stressUnderlyingPrice,int minPeakRewardBps,
                       BigDecimal brokerMarginReserveInr){}
    public record Proof(String provider,String accountScope,String ratioSide,String indexKey,
                        OptionLeg longLeg,OptionLeg shortLeg,IndexQuote index,
                        Risk risk,String contractDigest,Instant firstKnownAt){}
    private final JdbcTemplate jdbc;
    public FnoVerifiedRatioSpreadSourceStore(JdbcTemplate jdbc){
        this.jdbc=Objects.requireNonNull(jdbc);
    }
    public Optional<Proof> oneCurrent(String account,String side,String longKey,String shortKey,Instant at){
        if(account==null||!account.matches("[A-Za-z0-9_-]{8,80}")
           ||!("CALL".equals(side)||"PUT".equals(side))
           ||longKey==null||!longKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,80}")
           ||shortKey==null||!shortKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,80}")
           ||longKey.equals(shortKey)||at==null)
            throw new IllegalArgumentException("RATIO_RESEARCH_SOURCE_SCOPE_INVALID");
        Object[] a=new Object[(int)SQL.chars().filter(c->c=='?').count()];
        a[0]=account;a[1]=side;a[2]=longKey;a[3]=shortKey;
        Arrays.fill(a,4,a.length,Timestamp.from(at));
        a[7]=Date.valueOf(at.atZone(IST).toLocalDate());
        List<Proof> result=jdbc.query(SQL,(rs,i)->{
            LocalDate expiry=rs.getObject("expiry",LocalDate.class);
            int lot=rs.getInt("lot_size");
            return new Proof(rs.getString("provider_code"),rs.getString("account_scope"),rs.getString("ratio_side"),
                rs.getString("index_key"),
                new OptionLeg(rs.getString("long_key"),rs.getBigDecimal("long_strike"),
                    rs.getBigDecimal("long_tick"),
                    rs.getObject("long_listed_from",LocalDate.class),expiry,lot,
                    rs.getBigDecimal("long_bid"),rs.getBigDecimal("long_ask"),
                    rs.getLong("long_bid_depth_lots"),rs.getLong("long_ask_depth_lots"),
                    rs.getLong("long_oi_lots"),
                    rs.getTimestamp("long_exchange_at").toInstant(),
                    rs.getTimestamp("long_first_known_at").toInstant()),
                new OptionLeg(rs.getString("short_key"),rs.getBigDecimal("short_strike"),
                    rs.getBigDecimal("short_tick"),
                    rs.getObject("short_listed_from",LocalDate.class),expiry,lot,
                    rs.getBigDecimal("short_bid"),rs.getBigDecimal("short_ask"),
                    rs.getLong("short_bid_depth_lots"),rs.getLong("short_ask_depth_lots"),
                    rs.getLong("short_oi_lots"),
                    rs.getTimestamp("short_exchange_at").toInstant(),
                    rs.getTimestamp("short_first_known_at").toInstant()),
                new IndexQuote(rs.getBigDecimal("index_bid"),rs.getBigDecimal("index_ask"),
                    rs.getTimestamp("index_exchange_at").toInstant(),
                    rs.getTimestamp("index_first_known_at").toInstant()),
                new Risk(rs.getInt("buy_slippage_bps"),rs.getInt("sell_slippage_bps"),
                    rs.getInt("max_option_spread_bps"),
                    rs.getLong("min_bid_ask_depth_lots"),rs.getLong("min_oi_lots"),
                    rs.getInt("max_strike_distance_bps"),
                    rs.getBigDecimal("total_three_leg_fees_inr"),
                    rs.getBigDecimal("research_capital_inr"),
                    rs.getInt("max_loss_allocation_bps"),
                    rs.getBigDecimal("stress_underlying_price"),
                    rs.getInt("min_peak_reward_bps"),
                    rs.getBigDecimal("broker_margin_reserve_inr")),
                rs.getString("contract_source_sha256"),
                rs.getTimestamp("first_known_at").toInstant());
        },a);
        if(result==null||result.size()!=1)return Optional.empty();
        Proof p=result.getFirst();
        if(p==null||!account.equals(p.accountScope())||!side.equals(p.ratioSide())
           ||!longKey.equals(p.longLeg().key())
           ||!shortKey.equals(p.shortLeg().key())
           ||!"NSE_INDEX|Nifty 50".equals(p.indexKey())
           ||p.contractDigest()==null||!p.contractDigest().matches("[a-f0-9]{64}")
           ||p.firstKnownAt()==null||p.firstKnownAt().isAfter(at))
            return Optional.empty();
        return Optional.of(p);
    }
    public int purgeExpired(LocalDate day){
        return jdbc.update(
            "DELETE FROM fno_verified_nifty_ratio_spread_source WHERE retain_until < ?",
            Date.valueOf(Objects.requireNonNull(day)));
    }
    @Scheduled(cron="0 5 4 * * *",zone="Asia/Kolkata")
    public void scheduledRetentionPurge(){purgeExpired(LocalDate.now(IST));}
}
