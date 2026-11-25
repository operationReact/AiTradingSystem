package in.aitradingsystem.news.feature;

import java.time.Duration;
import java.time.Instant;

/**
 * Freshness of an included news item at its as-of instant, measured as the share of its validity
 * window already used by the event's age. Pure and deterministic.
 *
 * <p>FRESH: the first third of the validity window. AGING: the rest of the window. Items at or past
 * validity are not "stale" here: they are excluded earlier as EXCLUDED_EXPIRED by the policy. UNKNOWN
 * is returned for impossible inputs (missing or non-positive window, or an event after asOf), so an
 * unknown age is never presented as fresh.
 */
public final class NewsFreshness {
    private NewsFreshness() {}

    public enum Freshness { FRESH, AGING, UNKNOWN }

    public static Freshness of(Instant publishedAt, Instant validUntil, Instant asOf) {
        if (publishedAt == null || validUntil == null || asOf == null) return Freshness.UNKNOWN;
        Duration window = Duration.between(publishedAt, validUntil);
        if (window.isNegative() || window.isZero()) return Freshness.UNKNOWN;
        Duration age = Duration.between(publishedAt, asOf);
        if (age.isNegative()) return Freshness.UNKNOWN;
        return age.multipliedBy(3).compareTo(window) <= 0 ? Freshness.FRESH : Freshness.AGING;
    }
}
