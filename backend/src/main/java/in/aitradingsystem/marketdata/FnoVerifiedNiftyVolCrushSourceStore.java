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
 * STR-202 retrospective ONLY. Official event schedule's original first-known
 * must precede the event and pre/post separate licensed NIFTY+30d IV observations
 * must be sourced as-of. No synthetic market/calendar event and no broker order.
 */
@Component
public final class FnoVerifiedNiftyVolCrushSourceStore {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final String EVENT_SQL="""
SELECT s.* FROM fno_nifty_vol_crush_event_source s
 JOIN golden_data_provider provider ON provider.provider_code=s.provider_code AND provider.source_kind='EXTERNAL'
 JOIN golden_source_rights_receipt calendar_rights
 ON calendar_rights.receipt_id=s.calendar_rights_receipt_id
 AND calendar_rights.provider_id=provider.provider_id
 AND calendar_rights.source_domain='official_nifty_sensitive_event_calendar'
 JOIN golden_source_rights_receipt index_rights
 ON index_rights.receipt_id=s.index_rights_receipt_id
 AND index_rights.provider_id=provider.provider_id
 AND index_rights.source_domain='nse_nifty_underlying_tick'
 JOIN golden_source_rights_receipt chain_rights
 ON chain_rights.receipt_id=s.chain_rights_receipt_id
 AND chain_rights.provider_id=provider.provider_id
 AND chain_rights.source_domain='nse_fo_option_chain_depth_oi'
 JOIN golden_source_rights_receipt iv_rights
 ON iv_rights.receipt_id=s.iv_rights_receipt_id
 AND iv_rights.provider_id=provider.provider_id
 AND iv_rights.source_domain='nse_fo_option_implied_volatility'
 JOIN golden_source_rights_receipt normalization_rights
 ON normalization_rights.receipt_id=s.normalization_rights_receipt_id
 AND normalization_rights.provider_id=provider.provider_id
 AND normalization_rights.source_domain='nse_fo_iv_constant_tenor_normalization'
 WHERE s.event_key=? AND s.original_event_verified
 AND s.pre_iv_original_verified AND s.pre_index_original_verified AND s.pre_normalization_original_verified
 AND s.post_iv_original_verified AND s.post_index_original_verified AND s.post_normalization_original_verified
 AND s.pre_iv_sha256<>s.post_iv_sha256
 AND s.pre_index_sha256<>s.post_index_sha256
 AND s.event_end<=? AND s.first_known_at<=? AND s.captured_at<=?
 AND s.valid_until>=? AND s.retain_until>=?
 AND calendar_rights.permitted_use='MODEL_INFERENCE' AND calendar_rights.receipt_status='VERIFIED'
 AND calendar_rights.valid_from<=s.scheduled_at_first_known
 AND (calendar_rights.valid_to IS NULL OR calendar_rights.valid_to>s.scheduled_at_first_known)
 AND calendar_rights.verified_at<=s.scheduled_at_first_known AND calendar_rights.captured_at<=s.scheduled_at_first_known
 AND index_rights.permitted_use='MODEL_INFERENCE' AND index_rights.receipt_status='VERIFIED'
 AND index_rights.valid_from<=s.pre_first_known_at
 AND (index_rights.valid_to IS NULL OR index_rights.valid_to>s.pre_first_known_at)
 AND index_rights.verified_at<=s.pre_first_known_at AND index_rights.captured_at<=s.pre_first_known_at
 AND chain_rights.permitted_use='MODEL_INFERENCE' AND chain_rights.receipt_status='VERIFIED'
 AND chain_rights.valid_from<=s.pre_first_known_at
 AND (chain_rights.valid_to IS NULL OR chain_rights.valid_to>s.pre_first_known_at)
 AND chain_rights.verified_at<=s.pre_first_known_at AND chain_rights.captured_at<=s.pre_first_known_at
 AND iv_rights.permitted_use='MODEL_INFERENCE' AND iv_rights.receipt_status='VERIFIED'
 AND iv_rights.valid_from<=s.pre_first_known_at
 AND (iv_rights.valid_to IS NULL OR iv_rights.valid_to>s.pre_first_known_at)
 AND iv_rights.verified_at<=s.pre_first_known_at AND iv_rights.captured_at<=s.pre_first_known_at
 AND normalization_rights.permitted_use='MODEL_INFERENCE' AND normalization_rights.receipt_status='VERIFIED'
 AND normalization_rights.valid_from<=s.pre_first_known_at
 AND (normalization_rights.valid_to IS NULL OR normalization_rights.valid_to>s.pre_first_known_at)
 AND normalization_rights.verified_at<=s.pre_first_known_at AND normalization_rights.captured_at<=s.pre_first_known_at
 ORDER BY s.first_known_at DESC LIMIT 2
        """;
    private static final String HISTORY_SQL="""
SELECT h.* FROM fno_nifty_vol_crush_history_source h
 JOIN fno_nifty_vol_crush_event_source e ON e.evidence_id=h.event_evidence_id
 JOIN golden_data_provider provider ON provider.provider_code=e.provider_code AND provider.source_kind='EXTERNAL'
 JOIN golden_source_rights_receipt index_rights ON index_rights.receipt_id=h.index_rights_receipt_id
 AND index_rights.provider_id=provider.provider_id AND index_rights.source_domain='nse_nifty_underlying_tick'
 WHERE h.event_evidence_id=?
 AND h.original_index_verified AND h.index_first_known_at<e.event_start
 AND h.first_known_at<e.event_start AND h.captured_at<=?
 AND h.session_date<=(e.event_start AT TIME ZONE 'Asia/Kolkata')::date
 AND h.session_date>=(e.event_start AT TIME ZONE 'Asia/Kolkata')::date-INTERVAL '90 days'
 AND index_rights.permitted_use='MODEL_INFERENCE' AND index_rights.receipt_status='VERIFIED'
 AND index_rights.valid_from<=h.first_known_at
 AND (index_rights.valid_to IS NULL OR index_rights.valid_to>h.first_known_at)
 AND index_rights.verified_at<=h.first_known_at AND index_rights.captured_at<=h.first_known_at
 ORDER BY h.session_date DESC,h.first_known_at DESC LIMIT 371
        """;
    public record Event(long evidenceId,String provider,String eventKey,
        String category,String calendarProvider,Instant scheduleKnownAt,
        Instant startAt,Instant endAt,Instant firstKnownAt) {}
    public record Snapshot(String provider,BigDecimal spot,BigDecimal annualized30DayIv,
        Instant exchangeAt,Instant firstKnownAt) {}
    public record Historical(LocalDate session,String provider,BigDecimal open,
        BigDecimal high,BigDecimal low,BigDecimal close,Instant exchangeCloseAt,
        Instant firstKnownAt) {}
    public record Source(Event event,Snapshot pre,Snapshot post,List<Historical> history) {}

    private final JdbcTemplate jdbc;
    public FnoVerifiedNiftyVolCrushSourceStore(JdbcTemplate jdbc){
        this.jdbc=Objects.requireNonNull(jdbc);
    }
    public Optional<Source> oneCurrent(String eventKey,Instant at){
        if(at==null||eventKey==null||!eventKey.matches("[A-Za-z0-9._:-]{3,80}"))
            throw new IllegalArgumentException("VOL_CRUSH_EVENT_OR_AS_OF_INVALID");
        var today=at.atZone(IST).toLocalDate();
        List<Source> candidates=jdbc.query(EVENT_SQL,(rs,n)->{
            String provider=rs.getString("provider_code");
            var event=new Event(rs.getLong("evidence_id"),provider,rs.getString("event_key"),
                rs.getString("event_category"),rs.getString("calendar_provider"),
                rs.getTimestamp("scheduled_at_first_known").toInstant(),
                rs.getTimestamp("event_start").toInstant(),
                rs.getTimestamp("event_end").toInstant(),
                rs.getTimestamp("first_known_at").toInstant());
            var pre=new Snapshot(provider,rs.getBigDecimal("pre_spot"),
                rs.getBigDecimal("pre_iv_percent"),
                rs.getTimestamp("pre_exchange_at").toInstant(),
                rs.getTimestamp("pre_first_known_at").toInstant());
            var post=new Snapshot(provider,rs.getBigDecimal("post_spot"),
                rs.getBigDecimal("post_iv_percent"),
                rs.getTimestamp("post_exchange_at").toInstant(),
                rs.getTimestamp("post_first_known_at").toInstant());
            return new Source(event,pre,post,List.of());
        },eventKey,Timestamp.from(at),Timestamp.from(at),Timestamp.from(at),
            Timestamp.from(at),Date.valueOf(today));
        if(candidates==null||candidates.size()!=1||candidates.getFirst()==null)
            return Optional.empty();
        var p=candidates.getFirst();
        if(p.event()==null||p.pre()==null||p.post()==null
           ||!eventKey.equals(p.event().eventKey())||p.event().evidenceId()<1
           ||p.event().firstKnownAt()==null||p.event().firstKnownAt().isAfter(at))
            return Optional.empty();
        List<Historical> history=jdbc.query(HISTORY_SQL,(rs,n)->new Historical(
            rs.getObject("session_date",LocalDate.class),p.event().provider(),
            rs.getBigDecimal("index_open"),rs.getBigDecimal("index_high"),
            rs.getBigDecimal("index_low"),rs.getBigDecimal("index_close"),
            rs.getTimestamp("index_exchange_close_at").toInstant(),
            rs.getTimestamp("index_first_known_at").toInstant()),
            p.event().evidenceId(),Timestamp.from(at));
        if(history==null||history.size()<35||history.size()>370)return Optional.empty();
        LocalDate prev=null;
        for(var h:history){
            if(h==null||h.session()==null||!p.event().provider().equals(h.provider())
               ||prev!=null&&!h.session().isBefore(prev)
               ||h.firstKnownAt()==null||!h.firstKnownAt().isBefore(p.event().startAt()))
                return Optional.empty();
            prev=h.session();
        }
        var chronology=new ArrayList<>(history);
        Collections.reverse(chronology);
        return Optional.of(new Source(p.event(),p.pre(),p.post(),List.copyOf(chronology)));
    }
    public int purgeExpired(LocalDate date){
        Objects.requireNonNull(date);
        int history=jdbc.update(
            "DELETE FROM fno_nifty_vol_crush_history_source WHERE retain_until < ?",
            Date.valueOf(date));
        int events=jdbc.update(
            "DELETE FROM fno_nifty_vol_crush_event_source WHERE retain_until < ? AND NOT EXISTS "
             +"(SELECT 1 FROM fno_nifty_vol_crush_history_source h WHERE h.event_evidence_id=evidence_id)",
            Date.valueOf(date));
        return history+events;
    }
    @Scheduled(cron="0 23 4 * * *",zone="Asia/Kolkata")
    public void scheduledPurge(){purgeExpired(LocalDate.now(IST));}
}
