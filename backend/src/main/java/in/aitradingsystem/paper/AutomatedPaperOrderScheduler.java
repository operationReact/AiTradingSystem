package in.aitradingsystem.paper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls deterministic strategy candidates; the service itself still requires PAPER + AUTOMATED. */
@Component
@ConditionalOnProperty(prefix = "app.paper.automation", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class AutomatedPaperOrderScheduler {
    private static final Logger log = LoggerFactory.getLogger(AutomatedPaperOrderScheduler.class);
    private final AutomatedPaperOrderService service;

    public AutomatedPaperOrderScheduler(AutomatedPaperOrderService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.paper.automation.poll-ms:60000}")
    public void run() {
        var result = service.runOnce();
        if (result.automationEnabled() && (result.eligibleSignals() > 0 || result.failed() > 0)) {
            log.info("Automated PAPER run eligible={} submitted={} failed={}",
                result.eligibleSignals(), result.submitted(), result.failed());
        }
    }
}
