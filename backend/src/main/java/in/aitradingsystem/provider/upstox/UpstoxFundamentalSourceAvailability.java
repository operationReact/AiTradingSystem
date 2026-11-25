package in.aitradingsystem.provider.upstox;

import java.time.Instant;
import java.util.Objects;

/**
 * Read-only source coverage metadata, not a provider API client or research license.
 * Missing evidence is UNKNOWN, never an observed zero or a verified entitlement.
 */
public final class UpstoxFundamentalSourceAvailability {
    private UpstoxFundamentalSourceAvailability() {}

    public enum Domain { CURRENT_KEY_RATIO, HISTORICAL_KEY_RATIO, ISSUER_FILING, CORPORATE_ACTION }
    public enum Observation { NOT_PROBED, ENDPOINT_UNCONFIRMED, AUTHORIZED_NONZERO, AUTHORIZED_EMPTY, REJECTED, RATE_LIMITED }
    public enum Permission { UNKNOWN, READ_ONLY_CONFIRMED, RETENTION_CONFIRMED, TRAINING_CONFIRMED }
    public enum Readiness { NO_EVIDENCE, READ_OBSERVED_RIGHTS_BLOCKED, SOURCE_BLOCKED }

    public record Evidence(Domain domain, Observation observation, Permission permission,
                           Instant attemptedAt, Integer observedRecords, String sourceReference) {
        public Evidence {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(permission, "permission");
            if (observedRecords != null && observedRecords < 0)
                throw new IllegalArgumentException("Negative record count");
            if (observation == Observation.NOT_PROBED || observation == Observation.ENDPOINT_UNCONFIRMED) {
                if (attemptedAt != null || observedRecords != null)
                    throw new IllegalArgumentException("Unobserved source cannot claim an attempt or count");
            } else if (attemptedAt == null || sourceReference == null || sourceReference.isBlank()) {
                throw new IllegalArgumentException("Observed source needs timestamp and reference");
            }
            if (observation == Observation.AUTHORIZED_NONZERO &&
                    (observedRecords == null || observedRecords == 0))
                throw new IllegalArgumentException("Nonzero evidence requires positive count");
            if (observation == Observation.AUTHORIZED_EMPTY && !Integer.valueOf(0).equals(observedRecords))
                throw new IllegalArgumentException("Empty observation requires observed zero");
            if (observation != Observation.AUTHORIZED_NONZERO &&
                    observation != Observation.AUTHORIZED_EMPTY && observedRecords != null)
                throw new IllegalArgumentException("Failure is not a record count");
        }
    }

    /** Deliberately never authorizes storage, research, training or publication. */
    public static Readiness readOnlyReadiness(Evidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        return switch (evidence.observation()) {
            case AUTHORIZED_NONZERO, AUTHORIZED_EMPTY -> Readiness.READ_OBSERVED_RIGHTS_BLOCKED;
            case NOT_PROBED, ENDPOINT_UNCONFIRMED -> Readiness.NO_EVIDENCE;
            case REJECTED, RATE_LIMITED -> Readiness.SOURCE_BLOCKED;
        };
    }
}
