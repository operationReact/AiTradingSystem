package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * STR-201 genuine original-time OHLC + 30-day constant tenor ATM IV store.
 * At most ONE rights-attested revision per historical session may be used;
 * no fallback to source-unsupported estimated IV or synthetic 30d series.
 */
@Component
public final class FnoVerifiedNiftyIvRankSourceStore {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final String HISTORY_SQL="""
SELECT h.* FROM fno_nifty_30d_iv_rank_history_source h
 JOIN golden_data_provider provider ON provider.provider_code=h.provider_code AND provider.source_kind='EXTERNAL'
 JOIN golden_source_rights_receipt index_rights
 ON index_rights.receipt_id=h.index_rights_receipt_id
 AND index_rights.provider_id=provider.provider_id
 AND index_rights.source_domain='nse_nifty_underlying_tick'
 JOIN golden_source_rights_receipt iv_history_rights
 ON iv_history_rights.receipt_id=h.iv_history_rights_receipt_id
 AND iv_history_rights.provider_id=provider.provider_id
 AND iv_history_rights.source_domain='nse_fo_option_implied_volatility'
 JOIN golden_source_rights_receipt normalization_rights
 ON normalization_rights.receipt_id=h.normalization_rights_receipt_id
 AND normalization_rights.provider_id=provider.provider_id
 AND normalization_rights.source_domain='nse_fo_iv_constant_tenor_normalization'
 WHERE h.provider_code=?
 AND h.session_date<=? AND h.session_date>=?
 AND h.original_index_verified AND h.original_iv_verified AND h.source_iv_interpolation_verified
 AND h.source_method='LICENSED_ATM_30D_INTERPOLATION' AND h.constant_tenor_days=30
 AND h.original_index_sha256<>h.original_iv_sha256
 AND h.original_iv_sha256<>h.interpolation_source_sha256
 AND h.first_known_at<=? AND h.captured_at<=?
 AND index_rights.permitted_use='MODEL_INFERENCE'
 AND index_rights.receipt_status='VERIFIED'
 AND index_rights.valid_from<=h.first_known_at
 AND (index_rights.valid_to IS NULL OR index_rights.valid_to>h.first_known_at)
 AND index_rights.verified_at<=h.first_known_at
 AND index_rights.captured_at<=h.first_known_at AND iv_history_rights.permitted_use='MODEL_INFERENCE'
 AND iv_history_rights.receipt_status='VERIFIED'
 AND iv_history_rights.valid_from<=h.first_known_at
 AND (iv_history_rights.valid_to IS NULL OR iv_history_rights.valid_to>h.first_known_at)
 AND iv_history_rights.verified_at<=h.first_known_at
 AND iv_history_rights.captured_at<=h.first_known_at AND normalization_rights.permitted_use='MODEL_INFERENCE'
 AND normalization_rights.receipt_status='VERIFIED'
 AND normalization_rights.valid_from<=h.first_known_at
 AND (normalization_rights.valid_to IS NULL OR normalization_rights.valid_to>h.first_known_at)
 AND normalization_rights.verified_at<=h.first_known_at
 AND normalization_rights.captured_at<=h.first_known_at
 ORDER BY h.session_date DESC,h.first_known_at DESC LIMIT 371
        """;
    private static final String CURRENT_SQL="""
SELECT s.* FROM fno_nifty_30d_iv_rank_current_source s
 JOIN golden_data_provider provider ON provider.provider_code=s.provider_code AND provider.source_kind='EXTERNAL'
 JOIN golden_source_rights_receipt chain_rights
 ON chain_rights.receipt_id=s.chain_rights_receipt_id
 AND chain_rights.provider_id=provider.provider_id
 AND chain_rights.source_domain='nse_fo_option_chain_depth_oi'
 JOIN golden_source_rights_receipt current_iv_rights
 ON current_iv_rights.receipt_id=s.current_iv_rights_receipt_id
 AND current_iv_rights.provider_id=provider.provider_id
 AND current_iv_rights.source_domain='nse_fo_option_implied_volatility'
 JOIN golden_source_rights_receipt normalization_rights
 ON normalization_rights.receipt_id=s.normalization_rights_receipt_id
 AND normalization_rights.provider_id=provider.provider_id
 AND normalization_rights.source_domain='nse_fo_iv_constant_tenor_normalization'
 WHERE s.original_iv_verified AND s.original_normalization_verified
 AND s.index_key='NSE_INDEX|Nifty 50' AND s.constant_tenor_days=30
 AND s.source_method='LICENSED_ATM_30D_INTERPOLATION'
 AND s.iv_quote_source_sha256<>s.normalization_source_sha256
 AND s.first_known_at<=? AND s.exchange_at<=?
 AND s.valid_until>=? AND s.captured_at<=?
 AND s.retain_until>=?
 AND chain_rights.permitted_use='MODEL_INFERENCE'
 AND chain_rights.receipt_status='VERIFIED'
 AND chain_rights.valid_from<=s.first_known_at
 AND (chain_rights.valid_to IS NULL OR chain_rights.valid_to>s.first_known_at)
 AND chain_rights.verified_at<=s.first_known_at
 AND chain_rights.captured_at<=s.first_known_at AND current_iv_rights.permitted_use='MODEL_INFERENCE'
 AND current_iv_rights.receipt_status='VERIFIED'
 AND current_iv_rights.valid_from<=s.first_known_at
 AND (current_iv_rights.valid_to IS NULL OR current_iv_rights.valid_to>s.first_known_at)
 AND current_iv_rights.verified_at<=s.first_known_at
 AND current_iv_rights.captured_at<=s.first_known_at AND normalization_rights.permitted_use='MODEL_INFERENCE'
 AND normalization_rights.receipt_status='VERIFIED'
 AND normalization_rights.valid_from<=s.first_known_at
 AND (normalization_rights.valid_to IS NULL OR normalization_rights.valid_to>s.first_known_at)
 AND normalization_rights.verified_at<=s.first_known_at
 AND normalization_rights.captured_at<=s.first_known_at
 ORDER BY s.first_known_at DESC LIMIT 2
        """;
    public record Historical(LocalDate session,String provider,BigDecimal open,
        BigDecimal high,BigDecimal low,BigDecimal close,long volume,
        Instant exchangeCloseAt,Instant candleFirstKnownAt,
        BigDecimal annualized30DayIvPercent,Instant ivFirstKnownAt) {}
    public record Current(String provider,String indexKey,BigDecimal annualized30DayIvPercent,
        Instant exchangeAt,Instant firstKnownAt) {}
    public record Series(List<Historical> historical,Current current) {}
    private final JdbcTemplate jdbc;
    public FnoVerifiedNiftyIvRankSourceStore(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    /**
     * First resolve current provider uniquely; history must use that same
     * original issuer and has exactly 200..370 different completed sessions.
     * Source ambiguity (two current quotes, two revisions of one day) denies.
     */
    public Optional<Series> oneCurrent(Instant asOf){
        Objects.requireNonNull(asOf,"IV_RANK_SOURCE_AS_OF_REQUIRED");
        LocalDate today=asOf.atZone(IST).toLocalDate();
        List<Current> quotes=jdbc.query(CURRENT_SQL,(rs,n)->new Current(
            rs.getString("provider_code"),rs.getString("index_key"),
            rs.getBigDecimal("current_iv_percent"),
            rs.getTimestamp("exchange_at").toInstant(),
            rs.getTimestamp("first_known_at").toInstant()),
            Timestamp.from(asOf),Timestamp.from(asOf),Timestamp.from(asOf),
            Timestamp.from(asOf),Date.valueOf(today));
        if(quotes==null||quotes.size()!=1||quotes.getFirst()==null)return Optional.empty();
        Current q=quotes.getFirst();
        if(!provider(q.provider())||!positive(q.annualized30DayIvPercent())
           ||q.exchangeAt()==null||q.firstKnownAt()==null
           ||q.firstKnownAt().isAfter(asOf)||q.exchangeAt().isAfter(q.firstKnownAt()))
            return Optional.empty();
        List<Historical> rows=jdbc.query(HISTORY_SQL,(rs,n)->new Historical(
            rs.getObject("session_date",LocalDate.class),rs.getString("provider_code"),
            rs.getBigDecimal("index_open"),rs.getBigDecimal("index_high"),
            rs.getBigDecimal("index_low"),rs.getBigDecimal("index_close"),
            rs.getLong("index_volume"),
            rs.getTimestamp("index_exchange_close_at").toInstant(),
            rs.getTimestamp("index_first_known_at").toInstant(),
            rs.getBigDecimal("source_iv_percent"),
            rs.getTimestamp("source_iv_first_known_at").toInstant()),
            q.provider(),Date.valueOf(today),Date.valueOf(today.minusDays(550)),
            Timestamp.from(asOf),Timestamp.from(asOf));
        if(rows==null||rows.size()<200||rows.size()>370)return Optional.empty();
        LocalDate prev=null;
        for(Historical row:rows){
            if(row==null||row.session()==null||!q.provider().equals(row.provider())
               ||!positive(row.annualized30DayIvPercent())
               ||!positive(row.open())||!positive(row.high())||!positive(row.low())
               ||!positive(row.close())||row.volume()<0
               ||row.exchangeCloseAt()==null||row.candleFirstKnownAt()==null
               ||row.ivFirstKnownAt()==null
               ||row.candleFirstKnownAt().isAfter(asOf)||row.ivFirstKnownAt().isAfter(asOf)
               ||prev!=null&&!row.session().isBefore(prev))
                return Optional.empty();
            prev=row.session();
        }
        var chronological=new ArrayList<>(rows);
        Collections.reverse(chronological);
        return Optional.of(new Series(List.copyOf(chronological),q));
    }
    private static boolean positive(BigDecimal n){return n!=null&&n.signum()>0;}
    private static boolean provider(String x){
        return "UPSTOX_ANALYTICS".equals(x)||"ANGEL_ONE_SMARTAPI".equals(x);
    }
    public int purgeExpired(LocalDate today){
        Objects.requireNonNull(today);
        int a=jdbc.update("DELETE FROM fno_nifty_30d_iv_rank_history_source WHERE retain_until < ?",
            Date.valueOf(today));
        int b=jdbc.update("DELETE FROM fno_nifty_30d_iv_rank_current_source WHERE retain_until < ?",
            Date.valueOf(today));
        return a+b;
    }
    @Scheduled(cron="0 21 4 * * *",zone="Asia/Kolkata")
    public void scheduledPurge(){purgeExpired(LocalDate.now(IST));}
}
