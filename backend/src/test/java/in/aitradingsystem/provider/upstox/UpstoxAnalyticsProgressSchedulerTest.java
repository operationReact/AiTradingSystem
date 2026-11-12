package in.aitradingsystem.provider.upstox;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UpstoxAnalyticsProgressSchedulerTest {
    private final Clock clock =
            Clock.fixed(Instant.parse("2026-11-13T01:00:00Z"), ZoneOffset.UTC);
    private final UpstoxAnalyticsProgressRepository progress =
            mock(UpstoxAnalyticsProgressRepository.class);
    private final UpstoxGitHubProgressReader github = mock(UpstoxGitHubProgressReader.class);
    private final UpstoxAnalyticsSource source = mock(UpstoxAnalyticsSource.class);

    @Test
    void githubFailureIsReportedButNeverCallsMarketData() {
        UUID lease = UUID.randomUUID();
        when(progress.claim("GITHUB_DEVELOPMENT", clock.instant())).thenReturn(lease);
        when(github.inspect(clock.instant()))
                .thenThrow(new IllegalStateException("never expose provider exception"));
        var scheduler = new UpstoxAnalyticsProgressScheduler(
                progress, github, source, clock, true, false);
        scheduler.pollGithub();
        verify(progress).finish(eq("GITHUB_DEVELOPMENT"), eq(lease), eq("BLOCKED"),
                isNull(), isNull(), isNull(), eq("GITHUB_READ_UNAVAILABLE"), anyMap(),
                eq(clock.instant()));
        verifyNoInteractions(source);
    }

    @Test
    void githubDnsFailureIsPersistedAsSafeDiagnosticWithoutProviderCall() {
        UUID lease = UUID.randomUUID();
        when(progress.claim("GITHUB_DEVELOPMENT", clock.instant())).thenReturn(lease);
        when(github.inspect(clock.instant())).thenThrow(
                new UpstoxGitHubProgressReader.GitHubReadFailure(
                        "GITHUB_DNS_UNAVAILABLE"));
        var scheduler = new UpstoxAnalyticsProgressScheduler(
                progress, github, source, clock, true, false);
        scheduler.pollGithub();
        verify(progress).finish(eq("GITHUB_DEVELOPMENT"), eq(lease), eq("BLOCKED"),
                isNull(), isNull(), isNull(),
                eq("GITHUB_DNS_UNAVAILABLE"), eq(Map.of("retryable", true)),
                eq(clock.instant()));
        verifyNoInteractions(source);
    }

    @Test
    void disabledSourceNeverReadsTokenOrUpstox() {
        var scheduler = new UpstoxAnalyticsProgressScheduler(
                progress, github, source, clock, false, false);
        scheduler.pollGithub();
        scheduler.verifySource();
        verify(progress).disabled("GITHUB_DEVELOPMENT",
                "GITHUB_MONITOR_DISABLED", clock.instant());
        verify(progress).disabled("SOURCE_VERIFICATION",
                "OPERATOR_OPT_IN_REQUIRED", clock.instant());
        verifyNoInteractions(github, source);
    }

    @Test
    void claimsLeaseBeforeGithubReadAndRecordsSafeCounts() {
        UUID lease = UUID.randomUUID();
        when(progress.claim("GITHUB_DEVELOPMENT", clock.instant())).thenReturn(lease);
        when(github.inspect(clock.instant())).thenReturn(
                new UpstoxGitHubProgressReader.Result(5, 2, 1,
                        Map.of("scope", "RECENT_SAMPLE_NOT_TOTAL")));
        var scheduler = new UpstoxAnalyticsProgressScheduler(
                progress, github, source, clock, true, false);
        scheduler.pollGithub();
        verify(progress).finish(eq("GITHUB_DEVELOPMENT"), eq(lease), eq("VERIFIED"),
                eq(5L), eq(2L), eq(1L), isNull(), anyMap(), eq(clock.instant()));
        verifyNoInteractions(source);
    }
}
