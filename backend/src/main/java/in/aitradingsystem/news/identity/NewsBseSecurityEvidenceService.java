package in.aitradingsystem.news.identity;

import in.aitradingsystem.news.identity.NewsBseSecurityEvidenceRepository.SourceSecurity;
import in.aitradingsystem.news.identity.NewsIssuerCandidateCatalogueService.Candidate;
import in.aitradingsystem.news.identity.NewsIssuerCandidateCatalogueService.Snapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Matches an already-imported BSE cash-equity candidate against a dated,
 * independently sourced official BSE scrip-code + security-id + ISIN record.
 * Exact composite matching only: no guessed issuer, aliases or cross-exchange
 * equivalence. This service never authorizes paper or live orders.
 */
@Service
public class NewsBseSecurityEvidenceService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final long MAX_OFFICIAL_SOURCE_AGE_DAYS = 10;
    private final NewsIssuerCandidateCatalogueService candidates;
    private final NewsBseSecurityEvidenceRepository official;

    public NewsBseSecurityEvidenceService(
        NewsIssuerCandidateCatalogueService candidates,
        NewsBseSecurityEvidenceRepository official) {
        this.candidates = candidates;
        this.official = official;
    }

    /** All results are stamped with the research decision instant and their original source version. */
    public Coverage asOf(Instant decisionAt) {
        Objects.requireNonNull(decisionAt, "decisionAt");
        Snapshot snapshot = candidates.asOf(decisionAt);
        LocalDate decisionDate = decisionAt.atZone(IST).toLocalDate();
        List<SourceSecurity> sources = official.latestAvailableAt(decisionAt, decisionDate);
        if (sources.size() > NewsBseSecurityEvidenceRepository.MAX_SOURCE_ROWS) {
            throw new IllegalStateException("Truncated official BSE reference must not be accepted");
        }
        if (sources.isEmpty()) {
            return new Coverage(snapshot.catalogueVersion(), snapshot.sourceAvailableAt(),
                snapshot.bseCount(), 0, snapshot.bseCount(), "OFFICIAL_SOURCE_UNAVAILABLE",
                null, null, List.of());
        }

        SourceSecurity sample = sources.getFirst();
        LocalDate sourceDate = sample.sourceAsOf();
        Instant firstAvailable = sample.firstAvailableAt();
        UUID sourceVersion = sample.importId();
        if (sourceVersion == null || sourceDate == null || firstAvailable == null
            || sourceDate.isAfter(decisionDate) || firstAvailable.isAfter(decisionAt)
            || sample.archiveSha256() == null || !sample.archiveSha256().matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Invalid BSE source custody or point-in-time metadata");
        }
        for (SourceSecurity source : sources) {
            if (!sourceVersion.equals(source.importId())
                || !sourceDate.equals(source.sourceAsOf())
                || !firstAvailable.equals(source.firstAvailableAt())
                || !sample.archiveSha256().equals(source.archiveSha256())) {
                throw new IllegalStateException("Mixed BSE source versions in one result");
            }
        }
        long age = ChronoUnit.DAYS.between(sourceDate, decisionDate);
        if (age > MAX_OFFICIAL_SOURCE_AGE_DAYS) {
            return new Coverage(snapshot.catalogueVersion(), snapshot.sourceAvailableAt(),
                snapshot.bseCount(), 0, snapshot.bseCount(), "OFFICIAL_SOURCE_STALE",
                sourceVersion, sourceDate, List.of());
        }

        Map<String, SourceSecurity> exact = new HashMap<>();
        Set<String> conflicts = new HashSet<>();
        for (SourceSecurity source : sources) {
            String code = source.scripCode();
            if (code == null || !code.matches("[0-9]{1,15}")
                || source.securityId() == null || source.securityId().isBlank()
                || source.isin() == null
                || !source.isin().matches("[A-Z]{2}[A-Z0-9]{9}[0-9]")) {
                throw new IllegalStateException("Invalid official BSE security record");
            }
            if (exact.putIfAbsent(code, source) != null) conflicts.add(code);
        }
        List<Evidence> verified = new ArrayList<>();
        for (Candidate candidate : snapshot.candidates()) {
            if (!"BSE".equals(candidate.exchange()) || !"BSE_CM".equals(candidate.exchangeSegment())) {
                continue;
            }
            String code = candidate.brokerToken();
            if (code == null || conflicts.contains(code)) continue;
            SourceSecurity source = exact.get(code);
            if (source == null || !source.securityId().equalsIgnoreCase(candidate.tradingSymbol())) continue;
            verified.add(new Evidence(candidate.instrumentId(), candidate.brokerToken(),
                candidate.tradingSymbol(), source.isin(), source.importId(), source.sourceAsOf(),
                source.firstAvailableAt(), source.archiveSha256(), "OFFICIAL_BSE_SECURITY_ISIN"));
        }
        int matched = verified.size();
        int remaining = snapshot.bseCount() - matched;
        if (remaining < 0) throw new IllegalStateException("Official matching exceeds BSE candidate count");
        return new Coverage(snapshot.catalogueVersion(), snapshot.sourceAvailableAt(),
            snapshot.bseCount(), matched, remaining,
            remaining == 0 ? "BSE_SECURITY_MATCHES_COMPLETE" : "BSE_SECURITY_MATCHES_PARTIAL",
            sourceVersion, sourceDate, List.copyOf(verified));
    }

    public record Evidence(long brokerInstrumentId, String brokerToken, String tradingSymbol,
                           String verifiedIsin, UUID bseSourceVersion, LocalDate bseSourceAsOf,
                           Instant sourceFirstAvailableAt, String sourceHash,
                           String evidenceStatus) {}

    /**
     * verifiedSecurityMatches deliberately does NOT mean verifiedIssuerCount.
     * The issuer behind one share series still requires the separately attested
     * legal-entity/lineage relationship, including historical corporate actions.
     */
    public record Coverage(UUID brokerCatalogueVersion, Instant brokerCatalogueAvailableAt,
                           int bseCandidateCount, int verifiedSecurityMatches,
                           int unmatchedCandidates, String coverageStatus,
                           UUID bseSourceVersion, LocalDate bseSourceAsOf, List<Evidence> matches) {}
}
