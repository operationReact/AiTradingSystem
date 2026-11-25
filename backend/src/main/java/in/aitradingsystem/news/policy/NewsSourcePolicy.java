package in.aitradingsystem.news.policy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Per-source rights decision. Default-deny: a source may be used for a purpose only when its recorded state
 * is APPROVED, the current instant is inside the effective window, and the purpose is listed as permitted.
 * An HTTP 200 response from a publisher is never evidence of permission.
 */
public final class NewsSourcePolicy {
    private NewsSourcePolicy() {}

    /** Purpose of processing. NLP_TRANSFER is the only route by which article text may reach an external model. */
    public enum Use { INGEST, DISPLAY, NLP_TRANSFER, RETAIN }

    public enum LicenseState { APPROVED, REVIEW_PENDING, REJECTED, REVOKED }

    /** A stored policy row. Empty optionals mean the field was not recorded. */
    public record Row(String sourceId, String publisher, LicenseState state, List<Use> permittedUses,
                       String reviewerRef, String termsRef, Optional<Instant> effectiveFrom,
                       Optional<Instant> effectiveTo, int contentTtlDays) {
        public Row {
            permittedUses = List.copyOf(permittedUses);
        }
    }

    public enum Decision {
        PERMIT,
        DENY_UNKNOWN_SOURCE,
        DENY_REVIEW_PENDING,
        DENY_REJECTED,
        DENY_REVOKED,
        DENY_NOT_EFFECTIVE,
        DENY_EXPIRED,
        DENY_USE_NOT_PERMITTED;

        public boolean permitted() {
            return this == PERMIT;
        }
    }

    /** Pure decision. A missing row, a non-approved state, an out-of-window instant or an unlisted use all deny. */
    public static Decision evaluate(Optional<Row> row, Use use, Instant at) {
        if (row.isEmpty()) return Decision.DENY_UNKNOWN_SOURCE;
        Row r = row.get();
        switch (r.state()) {
            case REVIEW_PENDING -> { return Decision.DENY_REVIEW_PENDING; }
            case REJECTED -> { return Decision.DENY_REJECTED; }
            case REVOKED -> { return Decision.DENY_REVOKED; }
            case APPROVED -> { }
        }
        if (r.effectiveFrom().isEmpty() || at.isBefore(r.effectiveFrom().get())) return Decision.DENY_NOT_EFFECTIVE;
        if (r.effectiveTo().isPresent() && !at.isBefore(r.effectiveTo().get())) return Decision.DENY_EXPIRED;
        if (!r.permittedUses().contains(use)) return Decision.DENY_USE_NOT_PERMITTED;
        return Decision.PERMIT;
    }
}
