package in.aitradingsystem.intelligence;

import in.aitradingsystem.intelligence.ResearchInferenceAttemptLedger.Status;
import in.aitradingsystem.intelligence.ResearchReceiptDiagnosticsService.ModelStatus;
import in.aitradingsystem.intelligence.ResearchReceiptDiagnosticsService.Report;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * V4-09/V64: one-time ADMIN/PAPER-only enrolled roster dispatch, research evidence.
 *
 * At most four per-case requests are in flight simultaneously, eight across
 * instances of this service in one JVM. Each worker calls the already-gated
 * #409 dispatch, which commits its V61 reservation before HTTP and never retries.
 * No outer transaction spans the model worker. If any outcome is unresolved,
 * timed out, rejected or stale, there is NO V62 finalization or order action.
 */
@Service
public class ResearchRosterDispatchService {
    public static final String CONTRACT = "v4-research-roster-dispatch.v1";
    private static final int MAX_PARALLEL_PER_CASE = 4;
    private static final int MAX_ROSTER = 10;
    private static final int BUDGET_SECONDS = 45;
    private static final Semaphore WORKER_PERMITS = new Semaphore(8, true);

    public record RosterDispatchView(
        String contractVersion, UUID sessionId, String caseKey,
        int expectedModels, int succeeded, int unresolved,
        String status, boolean matchedEvidenceFinalized,
        String caseFingerprint, boolean paperOrdersSubmitted,
        boolean canAuthorizeCapital) {}

    private final ResearchReceiptDiagnosticsService diagnostics;
    private final ResearchInferenceDispatchService singleModel;
    private final ResearchMatchedDecisionFinalizeService finalizer;
    private final boolean enabled;

    public ResearchRosterDispatchService(
            ResearchReceiptDiagnosticsService diagnostics,
            ResearchInferenceDispatchService singleModel,
            ResearchMatchedDecisionFinalizeService finalizer,
            @Value("${app.research.roster-dispatch.enabled:false}") boolean enabled) {
        this.diagnostics = diagnostics;
        this.singleModel = singleModel;
        this.finalizer = finalizer;
        this.enabled = enabled;
    }

    /**
     * Explicitly NOT @Transactional: independent single-model reservations
     * commit before remote calls. No replay of an already-reserved case/model.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public RosterDispatchView dispatchRoster(UUID sessionId, String caseKey) {
        if (!enabled) {
            throw new IllegalStateException("Research roster dispatch is disabled");
        }
        if (sessionId == null || caseKey == null
                || !caseKey.matches("^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$")) {
            throw new IllegalArgumentException("Exact session and bounded case key required");
        }

        // All preflight information is from the original Java V56–V63 chain.
        // A re-invocation after any reservation fails *before* network work.
        Report initial = diagnostics.inspect(sessionId, caseKey);
        if (!sessionId.equals(initial.sessionId())
                || !caseKey.equals(initial.caseKey())
                || !"ACTIVE".equals(initial.sessionSafetyState())
                || initial.matchedCaseFinalized()
                || !initial.sourceCaptured() || !initial.featuresFrozen()
                || initial.expectedModels() < 2 || initial.expectedModels() > MAX_ROSTER
                || initial.models().size() != initial.expectedModels()
                || initial.successfulReceipts() != 0
                || initial.models().stream().anyMatch(m ->
                    !"NOT_ATTEMPTED".equals(m.status()) || m.reservationId() != null)
                || initial.models().stream().filter(m ->
                    "PAPER_CHAMPION".equals(m.role())).count() != 1
                || initial.models().stream().anyMatch(m ->
                    !Set.of("CATBOOST", "LIGHTGBM").contains(m.family())
                        || !Set.of("PAPER_CHAMPION", "SHADOW_CHALLENGER").contains(m.role()))
                || initial.models().stream().map(ModelStatus::modelVersionId)
                    .distinct().count() != initial.expectedModels()) {
            throw new IllegalStateException(
                "Research roster is incomplete, already attempted or unsafe");
        }

        // Spring Method Security uses a thread-local authentication. Explicit
        // context propagation ensures each proxied #409 dispatch checks ADMIN.
        final SecurityContext caller = SecurityContextHolder.getContext();
        final List<Callable<ResearchInferenceDispatchService.DispatchView>> tasks =
            new ArrayList<>();
        for (ModelStatus member : initial.models()) {
            final UUID modelId = member.modelVersionId();
            tasks.add(() -> {
                boolean permit = false;
                try {
                    permit = WORKER_PERMITS.tryAcquire(5, TimeUnit.SECONDS);
                    if (!permit) {
                        throw new IllegalStateException("Research worker capacity exhausted");
                    }
                    SecurityContextHolder.setContext(caller);
                    return singleModel.dispatch(sessionId, caseKey, modelId);
                } finally {
                    SecurityContextHolder.clearContext();
                    if (permit) WORKER_PERMITS.release();
                }
            });
        }

        // Bounded parallelism: no DB transaction/lock across HTTP. A cancel or
        // uncertain worker outcome cannot authorize retry; V61 remains durable.
        ExecutorService workers = Executors.newFixedThreadPool(
            Math.min(MAX_PARALLEL_PER_CASE, initial.expectedModels()));
        try {
            List<Future<ResearchInferenceDispatchService.DispatchView>> futures =
                workers.invokeAll(tasks, BUDGET_SECONDS, TimeUnit.SECONDS);
            for (Future<ResearchInferenceDispatchService.DispatchView> future : futures) {
                if (future.isCancelled()) continue;
                try {
                    var result = future.get();
                    if (result == null || result.status() != Status.SUCCEEDED
                            || result.canAuthorizeCapital() || result.paperOrderSubmitted()
                            || result.matchedDecisionAppended()) {
                        // Preserve exact terminal/UNKNOWN evidence: no retries.
                        continue;
                    }
                } catch (ExecutionException | CancellationException ignored) {
                    // A reservation may have committed before an error.
                    // The only authoritative status is fresh V61/V63 evidence.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // Existing reservations remain UNKNOWN if no receipt was recorded.
            throw new IllegalStateException(
                "Roster dispatch interrupted; inspect immutable receipts, never retry");
        } finally {
            workers.shutdownNow();
        }

        Report after = diagnostics.inspect(sessionId, caseKey);
        if (after.sessionSafetyRevision() != initial.sessionSafetyRevision()
                || !"ACTIVE".equals(after.sessionSafetyState())
                || !initial.enrollmentFingerprint().equals(after.enrollmentFingerprint())) {
            return incomplete(sessionId, caseKey, after);
        }
        if (after.successfulReceipts() == after.expectedModels()
                && after.unresolvedModels() == 0
                && after.models().stream().allMatch(m ->
                    "SUCCEEDED".equals(m.status()) && m.identityBound())) {
            // V62 takes the authoritative final PostgreSQL safety lock and
            // rechecks exact source, roster, causal time and receipt freshness.
            // A failure never falls back to a partial/fabricated decision.
            var finalized = finalizer.finalizeCase(sessionId, caseKey);
            if (finalized == null || finalized.canAuthorizeCapital()
                    || finalized.paperOrderSubmitted()
                    || finalized.modelPromotionAllowed()) {
                throw new IllegalStateException("Unsafe matched research finalization");
            }
            return new RosterDispatchView(CONTRACT, sessionId, caseKey,
                after.expectedModels(), after.successfulReceipts(),
                after.unresolvedModels(), "FINALIZED_RESEARCH_ONLY", true,
                finalized.caseFingerprint(), false, false);
        }
        return incomplete(sessionId, caseKey, after);
    }

    private static RosterDispatchView incomplete(UUID sessionId, String caseKey,
            Report report) {
        return new RosterDispatchView(CONTRACT, sessionId, caseKey,
            report.expectedModels(), report.successfulReceipts(),
            report.unresolvedModels(), "INCOMPLETE_NO_RETRY", false,
            null, false, false);
    }
}
