package in.aitradingsystem.paper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically supervises open positions; the service itself remains fail-closed outside PAPER + AUTOMATED. */
@Component
@ConditionalOnProperty(prefix = "app.paper.automation", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class AutomatedPaperPositionMonitorScheduler {
    private static final Logger log = LoggerFactory.getLogger(AutomatedPaperPositionMonitorScheduler.class);
    private final AutomatedPaperPositionMonitorService service;

    public AutomatedPaperPositionMonitorScheduler(AutomatedPaperPositionMonitorService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.paper.automation.position-monitor-ms:15000}")
    public void run() {
        var result = service.runOnce();
        if (result.automationEnabled()
                && (result.triggered() > 0 || result.rejectedOrFailed() > 0 || result.staleSkipped() > 0)) {
            log.info(
                "Automated PAPER position monitor checked={} triggered={} submitted={} replayed={} rejected={} stale={} emergencyStopped={}",
                result.positionsChecked(), result.triggered(), result.submitted(), result.replayed(),
                result.rejectedOrFailed(), result.staleSkipped(), result.emergencyStopped());
        }
    }
}
