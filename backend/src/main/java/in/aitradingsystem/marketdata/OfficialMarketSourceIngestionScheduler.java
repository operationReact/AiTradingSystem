package in.aitradingsystem.marketdata;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OfficialMarketSourceIngestionScheduler {
    private static final Logger log = LoggerFactory.getLogger(OfficialMarketSourceIngestionScheduler.class);

    private final OfficialMarketSourceIngestionService ingestion;
    private final boolean enabled;
    private final int maxRequestsPerRun;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public OfficialMarketSourceIngestionScheduler(
            OfficialMarketSourceIngestionService ingestion,
            @Value("${app.market-data.official-sources.scheduler.enabled:false}") boolean enabled,
            @Value("${app.market-data.official-sources.scheduler.max-requests-per-run:25}") int maxRequestsPerRun) {
        if (maxRequestsPerRun < 1 || maxRequestsPerRun > 25) {
            throw new IllegalArgumentException("Official source scheduler request limit must be between 1 and 25");
        }
        this.ingestion = ingestion;
        this.enabled = enabled;
        this.maxRequestsPerRun = maxRequestsPerRun;
    }

    @Scheduled(
            cron = "${app.market-data.official-sources.scheduler.cron:0 30 16 * * MON-FRI}",
            zone = "${app.market-data.official-sources.scheduler.zone:Asia/Kolkata}")
    public void ingestAfterClose() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        try {
            List<OfficialMarketSourceIngestionService.SourceRequest> requests = ingestion.scheduledRequests();
            int attempted = 0;
            for (OfficialMarketSourceIngestionService.SourceRequest request : requests) {
                if (attempted >= maxRequestsPerRun) break;
                attempted++;
                try {
                    ingestion.ingest(request);
                } catch (RuntimeException ex) {
                    log.warn("Official market source ingestion failed for family={} variant={}: {}",
                            request.family(), request.variant(), ex.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }
}
