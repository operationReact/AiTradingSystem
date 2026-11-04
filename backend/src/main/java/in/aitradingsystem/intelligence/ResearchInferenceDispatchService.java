package in.aitradingsystem.intelligence;

import in.aitradingsystem.governance.MatchedCausalFeatureProjectionService;
import in.aitradingsystem.governance.MatchedCausalFeatureProjectionService.ProjectionView;
import in.aitradingsystem.intelligence.ResearchInferenceAttemptLedger.FailureCode;
import in.aitradingsystem.intelligence.ResearchInferenceAttemptLedger.Reservation;
import in.aitradingsystem.intelligence.ResearchInferenceAttemptLedger.Status;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Bounded, ADMIN-only, research-only dispatch of exactly one registered model.
 * The V61 intent commits in a separate transaction BEFORE Python is contacted.
 * Transport occurs without DB row locks. Duplicate attempts (including UNKNOWN)
 * are refused by V61 uniqueness; this service NEVER retries uncertain requests.
 *
 * An individual successful receipt is NOT sufficient to append V56 matched
 * decisions. The full-roster atomic match/soak/promotion gates are separate work.
 */
@Service
public class ResearchInferenceDispatchService {
    public record DispatchView(String contractVersion, UUID reservationId,
        UUID sessionId, String caseKey, UUID modelVersionId, Status status,
        String reasonCode, String responseFingerprint,
        boolean canAuthorizeCapital, boolean matchedDecisionAppended,
        boolean paperOrderSubmitted) {}

    public static final String CONTRACT = "v4-research-inference-dispatch.v1";

    private final MatchedCausalFeatureProjectionService projections;
    private final ResearchInferenceAttemptLedger ledger;
    private final ResearchArtifactInferenceClient client;
    private final boolean workerEnabled;

    public ResearchInferenceDispatchService(
            MatchedCausalFeatureProjectionService projections,
            ResearchInferenceAttemptLedger ledger,
            ResearchArtifactInferenceClient client,
            @Value("${app.research.artifact-worker.enabled:false}") boolean workerEnabled) {
        this.projections = projections;
        this.ledger = ledger;
        this.client = client;
        this.workerEnabled = workerEnabled;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public DispatchView dispatch(UUID sessionId, String caseKey, UUID modelVersionId) {
        if (!workerEnabled) {
            throw new IllegalStateException("Research worker dispatch is disabled");
        }
        if (sessionId == null || modelVersionId == null || caseKey == null
                || !caseKey.matches("^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$")) {
            throw new IllegalArgumentException("Exact session, case and model required");
        }

        // Untrusted HTTP or request JSON never supplies a feature vector or
        // artifact path. All scoring inputs originate from frozen Java DB rows.
        ProjectionView frozen = projections.byCase(sessionId, caseKey);
        Reservation reservation = ledger.reserve(sessionId, caseKey, modelVersionId);
        // reserve() committed before this method reaches transport. Do not put
        // @Transactional on this method, or any caller around this boundary.
        if (!reservation.sourceInputFingerprint().equals(frozen.sourceInputFingerprint())
                || !reservation.featurePayloadFingerprint().equals(
                    frozen.featurePayloadFingerprint())
                || !reservation.featureAsOf().equals(frozen.featureAsOf())
                || !reservation.caseKey().equals(frozen.caseKey())
                || !reservation.sessionId().equals(frozen.sessionId())) {
            return failure(reservation, Status.REJECTED,
                FailureCode.RESPONSE_REJECTED);
        }

        final ResearchArtifactInferenceClient.Result result;
        try {
            // Python worker is opt-in, authenticated and nonredirecting;
            // its client never retries UNKNOWN transport outcomes.
            result = client.score(modelVersionId, frozen);
        } catch (IntelligenceUnavailableException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("UNKNOWN")) {
                return failure(reservation, Status.UNKNOWN,
                    FailureCode.TRANSPORT_UNKNOWN);
            }
            return failure(reservation, Status.REJECTED,
                FailureCode.RESPONSE_REJECTED);
        } catch (RuntimeException ex) {
            // Unexpected failures after reservation are always uncertain.
            // Never expose arbitrary exception text, remote response or token.
            return failure(reservation, Status.UNKNOWN,
                FailureCode.TRANSPORT_UNKNOWN);
        }

        try {
            var stored = ledger.succeed(reservation.reservationId(), result);
            return new DispatchView(CONTRACT, reservation.reservationId(),
                sessionId, caseKey, modelVersionId, stored.status(),
                stored.reasonCode(), stored.responseFingerprint(),
                false, false, false);
        } catch (IllegalStateException ex) {
            // An ADMIN may have PAUSED/QUARANTINED this session while the
            // worker was running; no late success may become accepted evidence.
            return failure(reservation, Status.REJECTED,
                FailureCode.SESSION_REVOKED);
        }
        // Other receipt storage errors intentionally propagate. The reservation
        // remains durable and, if receipt absent, outcome is UNKNOWN. No retry.
    }

    private DispatchView failure(Reservation reservation, Status status, FailureCode reason) {
        var receipt = ledger.fail(reservation.reservationId(), status, reason, null);
        return new DispatchView(CONTRACT, reservation.reservationId(),
            reservation.sessionId(), reservation.caseKey(),
            reservation.modelVersionId(), receipt.status(), receipt.reasonCode(),
            null, false, false, false);
    }
}
