package in.aitradingsystem.provider.upstox;

import java.time.Instant;
import java.util.Objects;

/**
 * Read-only provider diagnostics combining independently reported source and
 * freshness evidence. No API call, entitlement discovery, retention or training.
 * An accepted zero response is observed-empty, never missing or successful research.
 */
public final class UpstoxFundamentalSourceStatus {
    private UpstoxFundamentalSourceStatus() {}

    public enum State {
        NOT_OBSERVED, ENDPOINT_UNCONFIRMED, REJECTED, RATE_LIMITED,
        INVALID_RECEIPT, STALE_RECEIPT, VERIFIED_EMPTY_READ_ONLY,
        VERIFIED_NONZERO_READ_ONLY
    }

    public record Status(State state, Integer observedRecords, Instant lastAttemptAt,
                         String sourceReference, boolean researchPermitted) {}

    public static Status assess(UpstoxFundamentalSourceAvailability.Evidence source,
                                UpstoxFundamentalObservationAudit.Observation receipt) {
        Objects.requireNonNull(source, "source");
        var observation = source.observation();
        State state = switch (observation) {
            case NOT_PROBED -> State.NOT_OBSERVED;
            case ENDPOINT_UNCONFIRMED -> State.ENDPOINT_UNCONFIRMED;
            case REJECTED -> State.REJECTED;
            case RATE_LIMITED -> State.RATE_LIMITED;
            case AUTHORIZED_NONZERO, AUTHORIZED_EMPTY -> assessAccepted(source, receipt);
        };
        Integer count = state == State.VERIFIED_EMPTY_READ_ONLY ||
                state == State.VERIFIED_NONZERO_READ_ONLY ? source.observedRecords() : null;
        return new Status(state, count, source.attemptedAt(), source.sourceReference(), false);
    }

    private static State assessAccepted(UpstoxFundamentalSourceAvailability.Evidence source,
                                        UpstoxFundamentalObservationAudit.Observation receipt) {
        if (receipt == null || !receipt.responseAccepted()
                || !Objects.equals(source.attemptedAt(), receipt.attemptedAt())
                || !Objects.equals(source.observedRecords(), receipt.observedCount())) {
            return State.INVALID_RECEIPT;
        }
        var result = UpstoxFundamentalObservationAudit.evaluate(receipt);
        if (result == UpstoxFundamentalObservationAudit.Result.STALE)
            return State.STALE_RECEIPT;
        if (result != UpstoxFundamentalObservationAudit.Result.RECENT_OBSERVATION)
            return State.INVALID_RECEIPT;
        return source.observation() == UpstoxFundamentalSourceAvailability.Observation.AUTHORIZED_EMPTY
                ? State.VERIFIED_EMPTY_READ_ONLY : State.VERIFIED_NONZERO_READ_ONLY;
    }
}
