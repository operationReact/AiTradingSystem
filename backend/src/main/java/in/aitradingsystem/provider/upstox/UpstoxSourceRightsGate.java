package in.aitradingsystem.provider.upstox;

import java.time.Instant;
import java.util.Objects;

/**
 * Fail-closed source-use authorization for canonical research ingestion.
 * Successful API authentication is never evidence of storage or training rights.
 * This gate does not acquire permissions; grants must be independently verified.
 */
public final class UpstoxSourceRightsGate {
    private UpstoxSourceRightsGate() {}

    public enum Use { EPHEMERAL_READ, RETAIN, TRAIN, PUBLISH }
    public enum GrantStatus { UNVERIFIED, VERIFIED, REVOKED }

    public record Grant(String source, Use use, GrantStatus status,
                        String evidenceReference, Instant validUntil) {
        public Grant {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(use, "use");
            Objects.requireNonNull(status, "status");
            if (source.isBlank()) throw new IllegalArgumentException("source required");
        }
    }

    /** Caller supplies independent source/use-specific rights evidence. */
    public static void requireAllowed(String source, Use requestedUse,
                                      Grant grant, Instant now) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(requestedUse, "requested use");
        Objects.requireNonNull(now, "now");
        if (grant == null || !source.equals(grant.source())
                || requestedUse != grant.use()
                || grant.status() != GrantStatus.VERIFIED
                || grant.evidenceReference() == null || grant.evidenceReference().isBlank()
                || grant.validUntil() == null || !now.isBefore(grant.validUntil())) {
            throw new IllegalStateException("Source rights not verified for requested use");
        }
    }
}
