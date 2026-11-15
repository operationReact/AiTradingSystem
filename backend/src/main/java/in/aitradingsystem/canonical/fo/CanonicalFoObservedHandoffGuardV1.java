package in.aitradingsystem.canonical.fo;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Provider-neutral observed-metadata to consumer-identity boundary.
 * A match alone never authorizes use; both metadata and independent rights must pass.
 * This guard does not authorize execution, retention, publication, or training.
 */
public final class CanonicalFoObservedHandoffGuardV1 {
    public static final String VERSION = "minttick.canonical.fo-observed-handoff-guard.v1";

    private CanonicalFoObservedHandoffGuardV1() {}

    public static boolean eligibleForPrivatePaperResearch(
            CanonicalFoObservedMetadataGateV1.Observation observed,
            CanonicalFoIdentityHandoffV1 consumer,
            Instant decisionAt, LocalDate sessionDate) {
        if (observed == null || consumer == null || decisionAt == null || sessionDate == null) {
            return false;
        }
        if (!CanonicalFoObservedMetadataGateV1.permittedPrivateResearch(
                observed, decisionAt, sessionDate)) {
            return false;
        }
        try {
            return consumer.eligibleForPrivatePaperResearch(
                    CanonicalFoObservedMetadataGateV1.map(observed), decisionAt, sessionDate);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }
}
