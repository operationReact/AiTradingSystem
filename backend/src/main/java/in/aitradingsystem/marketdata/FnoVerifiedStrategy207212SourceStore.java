package in.aitradingsystem.marketdata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

/** Fail-closed rights-attested, exact original typed evaluator payload custody.
 * One source record only, NEVER aliases across strategies or invents Greek/IV.
 * All six independent original research-use rights must cover FIRST-KNOWN time.
 * The five distinct evaluators are solely responsible for their typed validation.
 */
@Component
public final class FnoVerifiedStrategy207212SourceStore {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final String SQL="""
SELECT s.provider_code,s.original_source_payload::text AS payload_json,
 s.original_first_known_at,s.original_payload_sha256
 FROM fno_verified_multistrategy_research_source s
 JOIN golden_data_provider p ON p.provider_code=s.provider_code AND p.source_kind='EXTERNAL'
 JOIN golden_source_rights_receipt r_index
  ON r_index.receipt_id=s.rights_index_receipt_id
 AND r_index.provider_id=p.provider_id
 AND r_index.source_domain='nse_nifty_underlying_tick'
 JOIN golden_source_rights_receipt r_options
  ON r_options.receipt_id=s.rights_options_receipt_id
 AND r_options.provider_id=p.provider_id
 AND r_options.source_domain='nse_fo_option_chain_depth_oi'
 JOIN golden_source_rights_receipt r_history
  ON r_history.receipt_id=s.rights_history_receipt_id
 AND r_history.provider_id=p.provider_id
 AND r_history.source_domain='nse_nifty_underlying_tick'
 JOIN golden_source_rights_receipt r_greeks
  ON r_greeks.receipt_id=s.rights_greeks_receipt_id
 AND r_greeks.provider_id=p.provider_id
 AND r_greeks.source_domain='nse_fo_option_implied_volatility'
 JOIN golden_source_rights_receipt r_master
  ON r_master.receipt_id=s.rights_master_receipt_id
 AND r_master.provider_id=p.provider_id
 AND r_master.source_domain='nse_fo_option_instrument_master'
 JOIN golden_source_rights_receipt r_auxiliary
  ON r_auxiliary.receipt_id=s.rights_auxiliary_receipt_id
 AND r_auxiliary.provider_id=p.provider_id
 AND r_auxiliary.source_domain=CASE
  WHEN s.strategy_id IN ('strategy.STR-207','strategy.STR-212')
    THEN 'nse_fo_listed_futures'
  WHEN s.strategy_id='strategy.STR-209'
    THEN 'official_nifty_option_expiry_cutoff'
  ELSE 'nse_broker_confirmed_positions'
 END
 WHERE s.strategy_id=? AND s.source_verified
 AND s.original_first_known_at<=? AND s.captured_at<=?
 AND s.valid_until>=? AND s.retain_until>=?
 AND r_index.receipt_status='VERIFIED' AND r_index.permitted_use='MODEL_INFERENCE'
 AND r_index.verified_at<=s.original_first_known_at AND r_index.captured_at<=s.original_first_known_at
 AND r_index.valid_from<=s.original_first_known_at
 AND (r_index.valid_to IS NULL OR r_index.valid_to>s.original_first_known_at)
 AND r_options.receipt_status='VERIFIED' AND r_options.permitted_use='MODEL_INFERENCE'
 AND r_options.verified_at<=s.original_first_known_at AND r_options.captured_at<=s.original_first_known_at
 AND r_options.valid_from<=s.original_first_known_at
 AND (r_options.valid_to IS NULL OR r_options.valid_to>s.original_first_known_at)
 AND r_history.receipt_status='VERIFIED' AND r_history.permitted_use='MODEL_INFERENCE'
 AND r_history.verified_at<=s.original_first_known_at AND r_history.captured_at<=s.original_first_known_at
 AND r_history.valid_from<=s.original_first_known_at
 AND (r_history.valid_to IS NULL OR r_history.valid_to>s.original_first_known_at)
 AND r_greeks.receipt_status='VERIFIED' AND r_greeks.permitted_use='MODEL_INFERENCE'
 AND r_greeks.verified_at<=s.original_first_known_at AND r_greeks.captured_at<=s.original_first_known_at
 AND r_greeks.valid_from<=s.original_first_known_at
 AND (r_greeks.valid_to IS NULL OR r_greeks.valid_to>s.original_first_known_at)
 AND r_master.receipt_status='VERIFIED' AND r_master.permitted_use='MODEL_INFERENCE'
 AND r_master.verified_at<=s.original_first_known_at AND r_master.captured_at<=s.original_first_known_at
 AND r_master.valid_from<=s.original_first_known_at
 AND (r_master.valid_to IS NULL OR r_master.valid_to>s.original_first_known_at)
 AND r_auxiliary.receipt_status='VERIFIED' AND r_auxiliary.permitted_use='MODEL_INFERENCE'
 AND r_auxiliary.verified_at<=s.original_first_known_at AND r_auxiliary.captured_at<=s.original_first_known_at
 AND r_auxiliary.valid_from<=s.original_first_known_at
 AND (r_auxiliary.valid_to IS NULL OR r_auxiliary.valid_to>s.original_first_known_at)
 ORDER BY s.original_first_known_at DESC LIMIT 2
        """;
    public record Proof(String strategyId,String provider,String json,
                        Instant originalFirstKnownAt,String declaredSha256) {}
    private final JdbcTemplate jdbc;
    public FnoVerifiedStrategy207212SourceStore(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    public Optional<Proof> oneCurrent(String strategyId,Instant now){
        if(now==null||strategyId==null||
                !strategyId.matches("strategy\\.STR-2(?:0[7-9]|1[0-2])"))
            throw new IllegalArgumentException("FNO_207_212_SOURCE_SCOPE_INVALID");
        List<Proof> found=jdbc.query(SQL,(rs,n)->new Proof(strategyId,
            rs.getString("provider_code"),rs.getString("payload_json"),
            rs.getTimestamp("original_first_known_at").toInstant(),
            rs.getString("original_payload_sha256")),
            strategyId,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),
            Date.valueOf(now.atZone(IST).toLocalDate()));
        if(found==null||found.size()!=1||found.getFirst()==null)return Optional.empty();
        Proof p=found.getFirst();
        if(p.json()==null||p.json().isBlank()||p.json().length()>1000000
            ||p.originalFirstKnownAt()==null||p.originalFirstKnownAt().isAfter(now)
            ||p.declaredSha256()==null||!p.declaredSha256().matches("[a-f0-9]{64}")
            ||!verifiedJsonbSha256(p.json(),p.declaredSha256())
            ||!("UPSTOX_ANALYTICS".equals(p.provider())||
                 "ANGEL_ONE_SMARTAPI".equals(p.provider())))
            return Optional.empty();
        return Optional.of(p);
    }
    /** Digest MUST be of PostgreSQL JSONB canonical ::text payload returned
     * by the query. The original importer stores exactly that digest.
     * No externally supplied digest is accepted without recomputation. */
    static boolean verifiedJsonbSha256(String original,String declared){
        if(original==null||declared==null||!declared.matches("[a-f0-9]{64}"))
            return false;
        try{
            var bytes=MessageDigest.getInstance("SHA-256")
                .digest(original.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(
                HexFormat.of().formatHex(bytes).getBytes(StandardCharsets.US_ASCII),
                declared.getBytes(StandardCharsets.US_ASCII));
        }catch(NoSuchAlgorithmException unavailable){
            return false;
        }
    }
    public int purgeExpired(LocalDate day){
        return jdbc.update("DELETE FROM fno_verified_multistrategy_research_source WHERE retain_until < ?",
            Date.valueOf(Objects.requireNonNull(day)));
    }
    @Scheduled(cron="0 23 4 * * *",zone="Asia/Kolkata")
    public void scheduledPurge(){purgeExpired(LocalDate.now(IST));}
}
