package in.aitradingsystem.provider.upstox;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Separate worker-development evidence monitor. It cannot start a backfill,
 * cannot promote paper readiness and never accesses a broker token.
 */
@Component
public class UpstoxWorkerProgressScheduler {
    private static final String CODE = "WORKER_REPORTS";
    private final UpstoxAnalyticsProgressRepository progress;
    private final UpstoxWorkerReportReader reader;
    private final Clock clock;
    private final boolean enabled;

    public UpstoxWorkerProgressScheduler(UpstoxAnalyticsProgressRepository progress,
            UpstoxWorkerReportReader reader, Clock clock,
            @Value("${MINTTICK_UPSTOX_WORKER_REPORTS_ENABLED:true}") boolean enabled) {
        this.progress = progress;
        this.reader = reader;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${minttick.upstox-analytics.workers.poll-ms:600000}",
               initialDelayString = "${minttick.upstox-analytics.workers.start-delay-ms:45000}")
    public void refresh() {
        Instant startedAt = clock.instant();
        if (!enabled) {
            progress.disabled(CODE, "GITHUB_WORKER_MONITOR_DISABLED", startedAt);
            return;
        }
        UUID lease = progress.claim(CODE, startedAt);
        if (lease == null) return;
        try {
            var result = reader.inspect(clock.instant());
            // VERIFIED means all five recent self-reports were observed,
            // NOT that data ingestion or PAPER runtime is ready.
            String state = result.fresh() == 5 ? "VERIFIED" : "PARTIAL";
            progress.finish(CODE, lease, state, 5L, result.fresh(),
                    5 - result.fresh(), result.fresh() == 5
                            ? null : "WORKER_REPORTS_MISSING_OR_STALE",
                    result.details(), clock.instant());
        } catch (RuntimeException ignored) {
            progress.finish(CODE, lease, "BLOCKED", null, null, null,
                    "GITHUB_WORKER_REPORT_UNAVAILABLE",
                    Map.of("source", "GITHUB_ISSUE_1012", "retryable", true),
                    clock.instant());
        }
    }
}
