package in.aitradingsystem.marketdata;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Verifies and appends independently signed STR-176 source-rights and exchange
 * calendar evidence. This is not a rights-granting API: only the separately
 * pinned reviewer can attest that the referenced provider evidence was checked.
 */
public final class Fno176AttestedFoundationIntake {
    private static final String VERSION="minttick.fno176.foundation.v1";
    private static final Set<String> DOMAINS=Set.of(
        "nse_fo_completed_ohlcv","nse_fo_final_oi","nse_fo_trading_calendar");
    private static final Set<String> USES=Set.of("RAW_RETENTION","MODEL_INFERENCE");
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public record RightsGrant(UUID receiptId,String sourceDomain,String permittedUse,
            String termsDigest,Instant validFrom,Instant validTo,String sourceReference) {}
    public record CalendarRecord(UUID evidenceId,LocalDate sessionDate,int sourceRevision,
            String sessionStatus,Instant sessionCloseAt,Instant sourcePublishedAt,
            Instant firstAvailableAt,String evidenceDigest,String sourceReference) {}
    public record Receipt(UUID providerId,String providerCode,String legalName,
            String reviewerId,Instant reviewedAt,List<RightsGrant> rights,
            List<CalendarRecord> calendar,byte[] signature) {
        public Receipt {
            rights=rights==null?List.of():List.copyOf(rights);
            calendar=calendar==null?List.of():List.copyOf(calendar);
            signature=signature==null?new byte[0]:signature.clone();
        }
        @Override public byte[] signature(){return signature.clone();}
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final PublicKey reviewer;
    private final Clock clock;

    public Fno176AttestedFoundationIntake(NamedParameterJdbcTemplate jdbc,
            PublicKey reviewer,Clock clock) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.reviewer=Objects.requireNonNull(reviewer);
        this.clock=Objects.requireNonNull(clock);
    }

    public static Receipt read(byte[] bytes) throws IOException {
        return JSON.readValue(bytes,Receipt.class);
    }

    /** Returns only after signature, scope, effective dates, and identities validate. */
    public int append(Receipt receipt,Set<LocalDate> requiredSessions) {
        validate(receipt,requiredSessions);
        var now=clock.instant();
        var provider=new MapSqlParameterSource().addValue("id",receipt.providerId())
            .addValue("code",receipt.providerCode()).addValue("name",receipt.legalName());
        jdbc.update("""
            INSERT INTO golden_data_provider(provider_id,provider_code,legal_name,source_kind)
            VALUES (:id,:code,:name,'EXTERNAL')
            ON CONFLICT (provider_code) DO NOTHING
            """,provider);
        Integer providerMatches=jdbc.queryForObject("""
            SELECT count(*) FROM golden_data_provider
             WHERE provider_id=:id AND provider_code=:code AND legal_name=:name
               AND source_kind='EXTERNAL'
            """,provider,Integer.class);
        if(providerMatches==null || providerMatches!=1)
            throw new IllegalArgumentException("PROVIDER_IDENTITY_CONFLICT");

        for(var grant:receipt.rights()) appendRight(receipt,grant,now);
        for(var cal:receipt.calendar()) appendCalendar(receipt,cal,now);
        return receipt.rights().size()+receipt.calendar().size();
    }

    private void appendRight(Receipt receipt,RightsGrant grant,Instant capturedAt) {
        var p=new MapSqlParameterSource().addValue("id",grant.receiptId())
            .addValue("provider",receipt.providerId()).addValue("domain",grant.sourceDomain())
            .addValue("use",grant.permittedUse()).addValue("terms",grant.termsDigest())
            .addValue("from",grant.validFrom()).addValue("to",grant.validTo())
            .addValue("reviewed",receipt.reviewedAt()).addValue("reviewer",receipt.reviewerId())
            .addValue("reference",grant.sourceReference()).addValue("captured",capturedAt);
        jdbc.update("""
            INSERT INTO golden_source_rights_receipt(receipt_id,provider_id,source_domain,
              permitted_use,receipt_status,terms_digest,valid_from,valid_to,verified_at,
              verified_by,source_reference,captured_at)
            VALUES (:id,:provider,:domain,:use,'VERIFIED',:terms,:from,:to,:reviewed,
              :reviewer,:reference,:captured)
            ON CONFLICT (receipt_id) DO NOTHING
            """,p);
        Integer matches=jdbc.queryForObject("""
            SELECT count(*) FROM golden_source_rights_receipt
             WHERE receipt_id=:id AND provider_id=:provider AND source_domain=:domain
               AND permitted_use=:use AND receipt_status='VERIFIED' AND terms_digest=:terms
               AND valid_from=:from AND valid_to IS NOT DISTINCT FROM :to
               AND verified_at=:reviewed AND verified_by=:reviewer
               AND source_reference=:reference
            """,p,Integer.class);
        if(matches==null || matches!=1)
            throw new IllegalArgumentException("RIGHTS_RECEIPT_CONFLICT");
    }

    private void appendCalendar(Receipt receipt,CalendarRecord cal,Instant capturedAt) {
        var p=new MapSqlParameterSource().addValue("id",cal.evidenceId())
            .addValue("provider",receipt.providerId()).addValue("domain","nse_fo_trading_calendar")
            .addValue("session",cal.sessionDate()).addValue("revision",cal.sourceRevision())
            .addValue("status",cal.sessionStatus()).addValue("close",cal.sessionCloseAt())
            .addValue("published",cal.sourcePublishedAt()).addValue("available",cal.firstAvailableAt())
            .addValue("digest",cal.evidenceDigest()).addValue("reviewed",receipt.reviewedAt())
            .addValue("reviewer",receipt.reviewerId()).addValue("reference",cal.sourceReference())
            .addValue("captured",capturedAt);
        jdbc.update("""
            INSERT INTO golden_historical_exchange_session_evidence(evidence_id,provider_id,
              source_domain,exchange,session_date,source_revision,session_status,session_close_at,
              source_published_at,first_available_at,calendar_evidence_digest,verified_at,
              verified_by,source_reference,captured_at)
            VALUES (:id,:provider,:domain,'NSE',:session,:revision,:status,:close,:published,
              :available,:digest,:reviewed,:reviewer,:reference,:captured)
            ON CONFLICT DO NOTHING
            """,p);
        Integer matches=jdbc.queryForObject("""
            SELECT count(*) FROM golden_historical_exchange_session_evidence
             WHERE evidence_id=:id AND provider_id=:provider AND source_domain=:domain
               AND exchange='NSE' AND session_date=:session AND source_revision=:revision
               AND session_status=:status AND session_close_at IS NOT DISTINCT FROM :close
               AND source_published_at=:published AND first_available_at=:available
               AND calendar_evidence_digest=:digest AND verified_at=:reviewed
               AND verified_by=:reviewer AND source_reference=:reference
            """,p,Integer.class);
        if(matches==null || matches!=1)
            throw new IllegalArgumentException("CALENDAR_EVIDENCE_CONFLICT");
    }

    private void validate(Receipt r,Set<LocalDate> requiredSessions) {
        var now=clock.instant();
        if(r==null || r.providerId()==null || r.providerCode()==null
                || !r.providerCode().matches("[A-Z0-9_]{3,80}")
                || r.legalName()==null || r.legalName().isBlank() || r.legalName().length()>240
                || r.reviewerId()==null || r.reviewerId().isBlank() || r.reviewerId().length()>160
                || r.reviewedAt()==null || r.reviewedAt().isAfter(now)
                || r.reviewedAt().isBefore(now.minus(7,ChronoUnit.DAYS))
                || r.signature().length!=64 || requiredSessions==null || requiredSessions.isEmpty()
                || requiredSessions.size()>32 || r.calendar().size()!=requiredSessions.size()
                || !r.calendar().stream().map(CalendarRecord::sessionDate).collect(java.util.stream.Collectors.toSet()).equals(requiredSessions))
            throw new IllegalArgumentException("FOUNDATION_RECEIPT_INVALID");
        var seen=new HashSet<String>();
        var rightIds=new HashSet<UUID>();
        if(r.rights().size()!=DOMAINS.size()*USES.size())
            throw new IllegalArgumentException("FOUNDATION_RIGHTS_SCOPE_INVALID");
        for(var grant:r.rights()) {
            if(grant==null || grant.receiptId()==null || !DOMAINS.contains(grant.sourceDomain())
                    || !USES.contains(grant.permittedUse())
                    || !seen.add(grant.sourceDomain()+"/"+grant.permittedUse())
                    || !rightIds.add(grant.receiptId())
                    || grant.termsDigest()==null || !grant.termsDigest().matches("[a-f0-9]{64}")
                    || grant.validFrom()==null || grant.validFrom().isAfter(now)
                    || grant.validTo()!=null && !grant.validTo().isAfter(now)
                    || !reference(grant.sourceReference()))
                throw new IllegalArgumentException("FOUNDATION_RIGHTS_SCOPE_INVALID");
        }
        var calendarDates=new HashSet<LocalDate>();
        var calendarIds=new HashSet<UUID>();
        for(var cal:r.calendar()) {
            if(cal==null || cal.evidenceId()==null || cal.sessionDate()==null
                    || !requiredSessions.contains(cal.sessionDate()) || !calendarDates.add(cal.sessionDate())
                    || !calendarIds.add(cal.evidenceId())
                    || cal.sourceRevision()<1 || !Set.of("TRADING","CLOSED").contains(cal.sessionStatus())
                    || cal.sessionStatus().equals("TRADING")!=(cal.sessionCloseAt()!=null)
                    || cal.sourcePublishedAt()==null || cal.firstAvailableAt()==null
                    || cal.sourcePublishedAt().isAfter(cal.firstAvailableAt())
                    || cal.firstAvailableAt().isAfter(r.reviewedAt())
                    || cal.sessionCloseAt()!=null && cal.sessionCloseAt().isAfter(now)
                    || cal.evidenceDigest()==null || !cal.evidenceDigest().matches("[a-f0-9]{64}")
                    || !reference(cal.sourceReference()))
                throw new IllegalArgumentException("FOUNDATION_CALENDAR_INVALID");
        }
        try {
            var verifier=Signature.getInstance("Ed25519");
            verifier.initVerify(reviewer);
            verifier.update(signingBytes(r));
            if(!verifier.verify(r.signature()))
                throw new IllegalArgumentException("FOUNDATION_SIGNATURE_INVALID");
        } catch(GeneralSecurityException | IOException invalid) {
            throw new IllegalArgumentException("FOUNDATION_SIGNATURE_INVALID");
        }
    }

    private static boolean reference(String value) {
        return value!=null && !value.isBlank() && value.length()<=240;
    }

    /** Deterministic length-prefixed Ed25519 signing format; array order is canonicalized. */
    static byte[] signingBytes(Receipt r) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            write(out,VERSION); write(out,r.providerId().toString()); write(out,r.providerCode());
            write(out,r.legalName()); write(out,r.reviewerId()); write(out,r.reviewedAt().toString());
            var rights=new ArrayList<>(r.rights());
            rights.sort(Comparator.comparing((RightsGrant g)->g.sourceDomain()+"/"+g.permittedUse()));
            out.writeInt(rights.size());
            for(var g:rights) {
                write(out,g.receiptId().toString()); write(out,g.sourceDomain()); write(out,g.permittedUse());
                write(out,g.termsDigest()); write(out,g.validFrom().toString());
                write(out,g.validTo()==null?"":g.validTo().toString()); write(out,g.sourceReference());
            }
            var calendar=new ArrayList<>(r.calendar());
            calendar.sort(Comparator.comparing(CalendarRecord::sessionDate));
            out.writeInt(calendar.size());
            for(var c:calendar) {
                write(out,c.evidenceId().toString()); write(out,c.sessionDate().toString());
                write(out,Integer.toString(c.sourceRevision())); write(out,c.sessionStatus());
                write(out,c.sessionCloseAt()==null?"":c.sessionCloseAt().toString());
                write(out,c.sourcePublishedAt().toString()); write(out,c.firstAvailableAt().toString());
                write(out,c.evidenceDigest()); write(out,c.sourceReference());
            }
        }
        return bytes.toByteArray();
    }

    private static void write(DataOutputStream out,String field) throws IOException {
        byte[] value=Objects.requireNonNull(field).getBytes(StandardCharsets.UTF_8);
        if(value.length>1024) throw new IllegalArgumentException("FOUNDATION_FIELD_UNBOUNDED");
        out.writeInt(value.length); out.write(value);
    }
}
