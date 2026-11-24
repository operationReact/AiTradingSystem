package in.aitradingsystem.api;

import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.news.feature.NewsInstrumentVerifiedEvidenceService;
import in.aitradingsystem.news.feature.NewsVerifiedListingEventEvidenceRepository;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only CUSTOMER Mintick Mind research evidence.
 *
 * <p>Caller supplies an actual broker instrument ID and decision cutoff. The
 * backend resolves the exact retained Angel master, a source-attested dated
 * exchange listing, then only independently verified V130 AFFECTED_ISSUER
 * receipts whose publishing rights and observation clocks permit that cutoff.
 * This controller never invokes a model or an order path.
 */
@RestController
@RequestMapping("/api/customer/mind/v1")
@Validated
public class CustomerMindVerifiedNewsController {
    public static final String CONTRACT = "mind.verified-news-evidence.v1";
    private final NewsInstrumentVerifiedEvidenceService verifiedNews;

    public CustomerMindVerifiedNewsController(NewsInstrumentVerifiedEvidenceService verifiedNews) {
        this.verifiedNews = verifiedNews;
    }

    @GetMapping("/verified-news-evidence")
    public VerifiedNewsResponse verifiedEvidence(
            @RequestParam @Min(1) long instrumentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        if (asOf.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Future historical evidence cutoffs are not supported");
        }

        final NewsInstrumentVerifiedEvidenceService.ResearchResult result;
        try {
            result = verifiedNews.verifiedForInstrument(instrumentId, asOf);
        } catch (InstrumentMasterException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Historical instrument evidence is unavailable");
        } catch (IllegalStateException unsafe) {
            // Ambiguous or oversized source proofs never become a customer
            // success response with invented issuer coverage.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Historical issuer evidence could not be independently verified");
        }

        List<PublisherProof> proofs = result.evidence().stream()
            .map(CustomerMindVerifiedNewsController::project)
            .toList();
        return new VerifiedNewsResponse(CONTRACT, result.instrumentId(),
            result.decisionAt(), result.catalogueVersion(), result.verificationStatus(),
            result.verifiedListingId(), proofs.size(),
            false, false, List.copyOf(proofs));
    }

    private static PublisherProof project(
            NewsVerifiedListingEventEvidenceRepository.Evidence row) {
        return new PublisherProof(
            row.eventId(), row.version(), row.listingId(), row.receiptId(),
            row.sourceId(), row.publisher(), row.title(), row.sourceUrl(),
            row.publishedAt(), row.firstObservedAt(), row.fetchedAt(),
            row.mappingFirstKnownAt(), row.category(), row.retraction());
    }

    public record PublisherProof(
        UUID eventId, int eventVersion, UUID verifiedListingId, UUID receiptId,
        String sourceId, String publisher, String headline, String originalUrl,
        Instant publisherPublishedAt, Instant firstObservedAt,
        Instant fetchedAt, Instant mappingFirstKnownAt,
        String eventCategory, boolean retracted) {}

    /**
     * includedInDecision=false and canAuthorizeCapital=false are hardcoded.
     * The customer-visible facts are research context, not a signal, order
     * instruction or retrospective approval of source redistribution rights.
     */
    public record VerifiedNewsResponse(
        String contractVersion, long instrumentId, Instant researchCutoff,
        UUID catalogueVersion, String verificationStatus, UUID verifiedListingId,
        int evidenceCount, boolean includedInDecision, boolean canAuthorizeCapital,
        List<PublisherProof> evidence) {}
}
