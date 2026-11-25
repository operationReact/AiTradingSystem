package in.aitradingsystem.marketdata;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class MarketDataReconciliationScheduler {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private final MarketDataReconciliationRepository repository;
    private final MarketDataReconciliationService reconciliation;
    private final Clock clock;
    private final boolean enabled;
    private final int maxInstruments;
    private final int lookbackDays;
    private final Duration lease;
    private final String interval;

    public MarketDataReconciliationScheduler(MarketDataReconciliationRepository repository,
            MarketDataReconciliationService reconciliation, Clock clock,
            @Value("${marketdata.reconciliation.scheduler.enabled:false}") boolean enabled,
            @Value("${marketdata.reconciliation.scheduler.max-instruments:25}") int maxInstruments,
            @Value("${marketdata.reconciliation.scheduler.lookback-days:5}") int lookbackDays,
            @Value("${marketdata.reconciliation.scheduler.lease-minutes:30}") int leaseMinutes,
            @Value("${marketdata.reconciliation.scheduler.interval:FIVE_MINUTE}") String interval) {
        if (maxInstruments < 1 || maxInstruments > 500) throw new IllegalArgumentException("max instruments must be between 1 and 500");
        if (lookbackDays < 1 || lookbackDays > 30) throw new IllegalArgumentException("lookback days must be between 1 and 30");
        if (leaseMinutes < 1 || leaseMinutes > 240) throw new IllegalArgumentException("lease minutes must be between 1 and 240");
        this.repository = repository;
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.enabled = enabled;
        this.maxInstruments = maxInstruments;
        this.lookbackDays = lookbackDays;
        this.lease = Duration.ofMinutes(leaseMinutes);
        this.interval = interval;
    }

    @Scheduled(cron = "${marketdata.reconciliation.scheduler.cron:0 15 16 * * MON-FRI}",
            zone = "${marketdata.reconciliation.scheduler.zone:Asia/Kolkata}")
    public void reconcileAfterClose() {
        if (!enabled) return;
        UUID owner = UUID.randomUUID();
        if (!repository.tryClaim(owner, lease)) return;
        try {
            Instant now = clock.instant();
            LocalDate marketDate = now.atZone(MARKET_ZONE).toLocalDate();
            Instant from = marketDate.minusDays(lookbackDays).atStartOfDay(MARKET_ZONE).toInstant();
            for (Long instrumentId : repository.activeCashInstrumentIds(maxInstruments)) {
                try {
                    reconciliation.reconcile(instrumentId, interval, from, now);
                } catch (RuntimeException ignored) {
                    // One bad instrument/provider response must not prevent the bounded batch from continuing.
                }
            }
        } finally {
            repository.release(owner);
        }
    }
}
