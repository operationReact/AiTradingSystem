package in.aitradingsystem.marketdata;

import in.aitradingsystem.tradingrooms.FnoEquityCollarV1;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Account- and pair-scoped independent combined cost proof: no fees
 * double-counted across long PE and short CE. Read-only, empty by default. */
@Component
public final class FnoVerifiedOwnedEquityCollarCostStore {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String SQL = """
        SELECT c.provider_code,c.account_scope,c.equity_key,c.pe_key,c.ce_key,
               c.expiry,c.lot_size,c.first_known_at,
               c.equity_bid,c.equity_ask,c.equity_exchange_at,c.equity_first_known_at,
               c.downside_scenario_bps,c.drawdown_trigger_bps,
               c.put_buy_slippage_bps,c.call_sell_slippage_bps,
               c.max_each_option_spread_bps,c.minimum_bid_ask_depth_lots,
               c.minimum_open_interest_lots,c.max_strike_distance_bps,
               c.all_in_fees_per_collar_lot_inr,c.research_capital_inr,
               c.max_stock_notional_bps,c.max_net_debit_bps
          FROM fno_verified_owned_equity_collar_cost c
          JOIN golden_data_provider provider
            ON provider.provider_code=c.provider_code
           AND provider.source_kind='EXTERNAL'
          JOIN golden_source_rights_receipt fees
            ON fees.receipt_id=c.fee_cost_rights_receipt_id
           AND fees.provider_id=provider.provider_id
           AND fees.source_domain='nse_eq_owned_stock_collar_all_in_fees'
           AND fees.permitted_use='MODEL_INFERENCE'
           AND fees.receipt_status='VERIFIED'
          JOIN golden_source_rights_receipt quote_rights
            ON quote_rights.receipt_id=c.equity_quote_rights_receipt_id
           AND quote_rights.provider_id=provider.provider_id
           AND quote_rights.source_domain='nse_eq_quote_depth'
           AND quote_rights.permitted_use='MODEL_INFERENCE'
           AND quote_rights.receipt_status='VERIFIED'
         WHERE c.account_scope=? AND c.equity_key=? AND c.pe_key=?
           AND c.ce_key=? AND c.expiry=? AND c.lot_size=?
           AND c.combined_fees_not_double_counted
           AND c.physical_delivery_and_assignment_reviewed
           AND c.broker_settled_shares_reconciled
           AND c.first_known_at<=? AND c.captured_at<=? AND c.valid_until>=?
           AND c.equity_first_known_at<=?
           AND c.retain_until>=?
           AND fees.valid_from<=?
           AND (fees.valid_to IS NULL OR fees.valid_to>?)
           AND fees.verified_at<=? AND fees.captured_at<=?
           AND quote_rights.valid_from<=?
           AND (quote_rights.valid_to IS NULL OR quote_rights.valid_to>?)
           AND quote_rights.verified_at<=? AND quote_rights.captured_at<=?
         ORDER BY c.first_known_at DESC
         LIMIT 2
        """;
    public record CostProof(String provider,String accountScope,String stockKey,
                            String putKey,String callKey,LocalDate expiry,int lotSize,
                            Instant firstKnownAt,BigDecimal equityBid,BigDecimal equityAsk,
                            Instant equityExchangeAt,Instant equityFirstKnownAt,
                            FnoEquityCollarV1.Assumptions assumptions) {}
    private final JdbcTemplate jdbc;
    public FnoVerifiedOwnedEquityCollarCostStore(JdbcTemplate jdbc){
        this.jdbc=Objects.requireNonNull(jdbc);
    }
    public Optional<CostProof> oneCurrent(String account,String stock,String put,
                                          String call,LocalDate expiry,int lot,Instant now){
        if(account==null||!account.matches("[A-Za-z0-9_-]{8,80}")
           ||stock==null||!stock.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")
           ||put==null||!put.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
           ||call==null||!call.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
           ||put.equals(call)||expiry==null||lot<=0||now==null)
            throw new IllegalArgumentException("COLLAR_COST_SOURCE_SCOPE_INVALID");
        Object[] params = new Object[(int)SQL.chars().filter(ch->ch=='?').count()];
        params[0]=account; params[1]=stock;params[2]=put;params[3]=call;
        params[4]=Date.valueOf(expiry);params[5]=lot;
        Arrays.fill(params,6,params.length,Timestamp.from(now));
        params[10]=Date.valueOf(now.atZone(IST).toLocalDate());
        List<CostProof> result=jdbc.query(SQL,(rs,i)->new CostProof(
            rs.getString("provider_code"),rs.getString("account_scope"),
            rs.getString("equity_key"),rs.getString("pe_key"),rs.getString("ce_key"),
            rs.getObject("expiry",LocalDate.class),rs.getInt("lot_size"),
            rs.getTimestamp("first_known_at").toInstant(),
            rs.getBigDecimal("equity_bid"),rs.getBigDecimal("equity_ask"),
            rs.getTimestamp("equity_exchange_at").toInstant(),
            rs.getTimestamp("equity_first_known_at").toInstant(),
            new FnoEquityCollarV1.Assumptions(
                rs.getInt("downside_scenario_bps"),rs.getInt("drawdown_trigger_bps"),
                rs.getInt("put_buy_slippage_bps"),rs.getInt("call_sell_slippage_bps"),
                rs.getInt("max_each_option_spread_bps"),
                rs.getLong("minimum_bid_ask_depth_lots"),
                rs.getLong("minimum_open_interest_lots"),
                rs.getInt("max_strike_distance_bps"),
                rs.getBigDecimal("all_in_fees_per_collar_lot_inr"),
                rs.getBigDecimal("research_capital_inr"),
                rs.getInt("max_stock_notional_bps"),
                rs.getInt("max_net_debit_bps"))),params);
        if(result==null||result.size()!=1)return Optional.empty();
        var c=result.getFirst();
        if(c==null||!account.equals(c.accountScope())||!stock.equals(c.stockKey())
            ||!put.equals(c.putKey())||!call.equals(c.callKey())
            ||!expiry.equals(c.expiry())||lot!=c.lotSize()
            ||c.firstKnownAt()==null||c.firstKnownAt().isAfter(now)
            ||c.assumptions()==null||c.equityBid()==null||c.equityAsk()==null
            ||c.equityBid().signum()<=0||c.equityAsk().compareTo(c.equityBid())<0
            ||c.equityExchangeAt()==null||c.equityFirstKnownAt()==null
            ||c.equityFirstKnownAt().isAfter(now)
            ||c.equityExchangeAt().isAfter(c.equityFirstKnownAt())
            ||Duration.between(c.equityExchangeAt(),now)
                .compareTo(Duration.ofSeconds(180))>0)return Optional.empty();
        return Optional.of(c);
    }
    public int purgeExpired(LocalDate today){
        return jdbc.update("DELETE FROM fno_verified_owned_equity_collar_cost WHERE retain_until < ?",
            Date.valueOf(Objects.requireNonNull(today)));
    }
    @Scheduled(cron="0 3 4 * * *",zone="Asia/Kolkata")
    public void scheduledRetentionPurge(){purgeExpired(LocalDate.now(IST));}
}
