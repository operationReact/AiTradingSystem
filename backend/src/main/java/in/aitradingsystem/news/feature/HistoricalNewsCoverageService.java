package in.aitradingsystem.news.feature;

import in.aitradingsystem.news.identity.NewsIssuerCandidateCatalogueService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Bounded read-only historical coverage measurement; no news scoring or execution. */
@Service
public class HistoricalNewsCoverageService {
    public static final int MAX_PAGE_SIZE = 100;
    private final NewsIssuerCandidateCatalogueService catalogue;
    private final NewsInstrumentVerifiedEvidenceService evidence;

    public HistoricalNewsCoverageService(NewsIssuerCandidateCatalogueService catalogue,
            NewsInstrumentVerifiedEvidenceService evidence) {
        this.catalogue = catalogue;
        this.evidence = evidence;
    }

    /**
     * The caller must use the returned catalogue version and fingerprint on subsequent pages.
     * A page is not a universe-wide coverage estimate. No missing or failed evidence is called neutral.
     */
    public CoveragePage measure(Instant asOf, int offset, int limit,
            UUID expectedVersion, String expectedFingerprint) {
        if (asOf == null || asOf.isAfter(Instant.now()) || offset < 0
                || limit < 1 || limit > MAX_PAGE_SIZE
                || (expectedVersion == null) != (expectedFingerprint == null)
                || (offset > 0 && expectedVersion == null)) {
            throw new IllegalArgumentException("Invalid historical coverage page or snapshot identity");
        }
        var snapshot = catalogue.asOf(asOf);
        if (expectedVersion != null && (!expectedVersion.equals(snapshot.catalogueVersion())
                || !Objects.equals(expectedFingerprint, snapshot.sourceFingerprint()))) {
            throw new IllegalStateException("Historical coverage catalogue changed");
        }
        var candidates = snapshot.candidates();
        if (offset > candidates.size() || candidates.size() != snapshot.candidateCount()
                || !asOf.equals(snapshot.requestedAsOf())) {
            throw new IllegalStateException("Inconsistent historical coverage snapshot");
        }
        var identities = new HashSet<Long>();
        for (var candidate : candidates) {
            if (!identities.add(candidate.instrumentId())) {
                throw new IllegalStateException("Duplicate historical instrument identity");
            }
        }
        int end = offset + Math.min(limit, candidates.size() - offset);
        int verifiedWithEvidence = 0;
        int verifiedWithoutEvidence = 0;
        int unverified = 0;
        int unavailable = 0;
        List<CoverageRow> rows = new ArrayList<>();
        for (var candidate : candidates.subList(offset, end)) {
            var result = evidence.verifiedForInstrument(candidate.instrumentId(), asOf);
            if (result.instrumentId() != candidate.instrumentId()
                    || !asOf.equals(result.decisionAt())
                    || !snapshot.catalogueVersion().equals(result.catalogueVersion())
                    || result.canAuthorizeCapital()
                    || result.verifiedEventReceipts() != result.evidence().size()) {
                throw new IllegalStateException("Inconsistent historical instrument evidence");
            }
            String status = result.verificationStatus();
            int count = result.verifiedEventReceipts();
            switch (status) {
                case "VERIFIED_LISTING_EVENT_EVIDENCE" -> {
                    if (result.verifiedListingId() == null || count < 1)
                        throw new IllegalStateException("Missing verified event evidence");
                    verifiedWithEvidence++;
                }
                case "VERIFIED_LISTING_NO_EVENT_EVIDENCE" -> {
                    if (result.verifiedListingId() == null || count != 0)
                        throw new IllegalStateException("Inconsistent empty evidence");
                    verifiedWithoutEvidence++;
                }
                case "LISTING_IDENTITY_UNVERIFIED" -> {
                    requireUnresolved(result);
                    unverified++;
                }
                case "UNVERIFIED_CATALOGUE_SOURCE", "INSTRUMENT_NOT_IN_ASOF_CATALOGUE", "NOT_CASH_EQUITY" -> {
                    requireUnresolved(result);
                    unavailable++;
                }
                default -> throw new IllegalStateException("Unknown historical verification status");
            }
            rows.add(new CoverageRow(candidate.instrumentId(), candidate.exchange(), status, count));
        }
        return new CoveragePage(snapshot.catalogueVersion(), snapshot.sourceFingerprint(), asOf,
            candidates.size(), offset, rows.size(), end < candidates.size() ? end : null,
            verifiedWithEvidence, verifiedWithoutEvidence, unverified, unavailable,
            offset == 0 && end == candidates.size(), false, List.copyOf(rows));
    }

    private static void requireUnresolved(NewsInstrumentVerifiedEvidenceService.ResearchResult result) {
        if (result.verifiedListingId() != null || result.verifiedEventReceipts() != 0)
            throw new IllegalStateException("Unresolved identity cannot carry evidence");
    }

    public record CoverageRow(long instrumentId, String exchange, String verificationStatus,
                              int verifiedEventReceipts) {}
    public record CoveragePage(UUID catalogueVersion, String catalogueFingerprint, Instant asOf,
            int totalCatalogueInstruments, int offset, int measuredInstruments, Integer nextOffset,
            int verifiedWithEvidence, int verifiedWithoutEvidence, int unverifiedIdentity,
            int unavailable, boolean completeUniverseInThisPage, boolean canAuthorizeCapital,
            List<CoverageRow> rows) {}
}
