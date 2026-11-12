package in.aitradingsystem.provider.upstox;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Independent progress schedulers. GitHub reads cannot block market data.
 * A source probe verifies safe counts only and never archives raw broker data.
 * Real full-universe ingestion must pass rights + coverage contracts separately.
 */
@Component
public class UpstoxAnalyticsProgressScheduler {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final String GITHUB = "GITHUB_DEVELOPMENT";
    private static final String SOURCE = "SOURCE_VERIFICATION";

    private final UpstoxAnalyticsProgressRepository progress;
    private final UpstoxGitHubProgressReader github;
    private final UpstoxAnalyticsSource source;
    private final Clock clock;
    private final boolean githubEnabled;
    private final boolean sourceProbeEnabled;

    public UpstoxAnalyticsProgressScheduler(
            UpstoxAnalyticsProgressRepository progress,
            UpstoxGitHubProgressReader github,
            UpstoxAnalyticsSource source, Clock clock,
            @Value("${MINTTICK_GITHUB_PROGRESS_ENABLED:true}") boolean githubEnabled,
            @Value("${MINTTICK_UPSTOX_PROGRESS_SOURCE_PROBE_ENABLED:false}") boolean sourceProbeEnabled) {
        this.progress = progress;
        this.github = github;
        this.source = source;
        this.clock = clock;
        this.githubEnabled = githubEnabled;
        this.sourceProbeEnabled = sourceProbeEnabled;
    }

    @Scheduled(fixedDelayString = "${minttick.upstox-analytics.progress.github-poll-ms:300000}",
               initialDelayString = "${minttick.upstox-analytics.progress.start-delay-ms:30000}")
    public void pollGithub() {
        Instant now = clock.instant();
        if (!githubEnabled) {
            progress.disabled(GITHUB, "GITHUB_MONITOR_DISABLED", now);
            return;
        }
        UUID lease = progress.claim(GITHUB, now);
        if (lease == null) return;
        try {
            UpstoxGitHubProgressReader.Result result = github.inspect(clock.instant());
            progress.finish(GITHUB, lease, "VERIFIED",
                    result.checked(), result.verified(), result.issues(),
                    null, result.details(), clock.instant());
        } catch (UpstoxGitHubProgressReader.GitHubReadFailure failure) {
            progress.finish(GITHUB, lease, "BLOCKED", null, null, null,
                    failure.reasonCode(), Map.of("retryable", true),
                    clock.instant());
        } catch (RuntimeException ignored) {
            progress.finish(GITHUB, lease, "BLOCKED", null, null, null,
                    "GITHUB_READ_UNAVAILABLE", Map.of("retryable", true),
                    clock.instant());
        }
    }

    @Scheduled(fixedDelayString = "${minttick.upstox-analytics.progress.source-poll-ms:1800000}",
               initialDelayString = "${minttick.upstox-analytics.progress.source-start-delay-ms:60000}")
    public void verifySource() {
        Instant now = clock.instant();
        if (!sourceProbeEnabled || !source.enabled()) {
            progress.disabled(SOURCE, "OPERATOR_OPT_IN_REQUIRED", now);
            return;
        }
        UUID lease = progress.claim(SOURCE, now);
        if (lease == null) return;
        try {
            LocalDate lastDay = now.atZone(MARKET_ZONE).toLocalDate().minusDays(1);
            int bars = source.dailyEquityCandles(
                    "NSE_EQ|INE002A01018", lastDay.minusDays(6), lastDay).size();
            int ratios = source.currentRatios("INE002A01018").size();
            boolean verified = bars > 0 && ratios > 0;
            progress.finish(SOURCE, lease, verified ? "VERIFIED" : "PARTIAL",
                    2L, (long) (bars > 0 ? 1 : 0) + (ratios > 0 ? 1 : 0),
                    verified ? 0L : 1L,
                    verified ? null : "SOURCE_READ_PARTIAL",
                    Map.of("validatedDailyCandles", bars, "currentRatios", ratios,
                           "windowFrom", lastDay.minusDays(6).toString(),
                           "windowThrough", lastDay.toString(),
                           "stored", false, "modelTrainingEnabled", false,
                           "orderExecutionEnabled", false),
                    clock.instant());
        } catch (RuntimeException ignored) {
            progress.finish(SOURCE, lease, "BLOCKED", null, null, null,
                    "SOURCE_READ_UNAVAILABLE", Map.of("retryable", true),
                    clock.instant());
        }
    }
}
