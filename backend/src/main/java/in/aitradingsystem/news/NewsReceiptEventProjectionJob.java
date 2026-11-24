package in.aitradingsystem.news;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Default-off, bounded projection of authorized RSS receipts into the immutable
 * news event ledger. Deployment must explicitly enable this after rights review.
 */
@Component
@ConditionalOnProperty(name = "app.news.event-ledger.enabled", havingValue = "true")
public class NewsReceiptEventProjectionJob {
    private static final Logger log = LoggerFactory.getLogger(NewsReceiptEventProjectionJob.class);
    private final NewsReceiptEventProjector projector;

    public NewsReceiptEventProjectionJob(NewsReceiptEventProjector projector) {
        this.projector = projector;
    }

    @Scheduled(fixedDelayString = "#{T(java.lang.Math).min(86400000, T(java.lang.Math).max(60000, ${app.news.event-ledger.interval-ms:300000}))}")
    public void project() {
        var result = projector.runOnce();
        if (result.failures() > 0 || result.rightsBlocked() > 0) {
            log.warn("RSS event-ledger projection incomplete: inserted={} duplicates={} failed={} blocked={}",
                result.inserted(), result.duplicates(), result.failures(), result.rightsBlocked());
        }
    }
}
