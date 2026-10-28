package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FeedFreshnessPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private final FeedFreshnessPolicy policy = new FeedFreshnessPolicy(Duration.ofSeconds(5));

    @Test
    void classifiesObservationAtBoundaryAsFresh() {
        assertThat(policy.classify(NOW.minusSeconds(5), NOW))
                .isEqualTo(FeedFreshnessPolicy.Status.FRESH);
    }

    @Test
    void classifiesObservationBeyondBoundaryAsStale() {
        assertThat(policy.classify(NOW.minusSeconds(5).minusNanos(1), NOW))
                .isEqualTo(FeedFreshnessPolicy.Status.STALE);
    }

    @Test
    void failsClosedForMissingOrFutureTimestamps() {
        assertThat(policy.classify(null, NOW)).isEqualTo(FeedFreshnessPolicy.Status.UNKNOWN);
        assertThat(policy.classify(NOW, null)).isEqualTo(FeedFreshnessPolicy.Status.UNKNOWN);
        assertThat(policy.classify(NOW.plusNanos(1), NOW)).isEqualTo(FeedFreshnessPolicy.Status.UNKNOWN);
    }

    @Test
    void rejectsNonPositiveFreshnessWindow() {
        assertThatThrownBy(() -> new FeedFreshnessPolicy(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedFreshnessPolicy(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
