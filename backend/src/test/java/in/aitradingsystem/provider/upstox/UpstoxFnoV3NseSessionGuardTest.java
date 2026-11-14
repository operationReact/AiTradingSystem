package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UpstoxFnoV3NseSessionGuardTest {
    @Test
    void acceptsOnlyWeekdayRegularSessionAndExcludesClosingBoundary() {
        assertThat(UpstoxFnoV3LiveFeedService.regularNseSession(
                Instant.parse("2026-11-16T03:44:59Z"))).isFalse();
        assertThat(UpstoxFnoV3LiveFeedService.regularNseSession(
                Instant.parse("2026-11-16T03:45:00Z"))).isTrue();
        assertThat(UpstoxFnoV3LiveFeedService.regularNseSession(
                Instant.parse("2026-11-16T09:59:59Z"))).isTrue();
        assertThat(UpstoxFnoV3LiveFeedService.regularNseSession(
                Instant.parse("2026-11-16T10:00:00Z"))).isFalse();
    }

    @Test
    void rejectsWeekendAndNullEvenInsideSessionClockTime() {
        assertThat(UpstoxFnoV3LiveFeedService.regularNseSession(
                Instant.parse("2026-11-14T05:00:00Z"))).isFalse();
        assertThat(UpstoxFnoV3LiveFeedService.regularNseSession(
                Instant.parse("2026-11-15T05:00:00Z"))).isFalse();
        assertThat(UpstoxFnoV3LiveFeedService.regularNseSession(null)).isFalse();
    }
}
