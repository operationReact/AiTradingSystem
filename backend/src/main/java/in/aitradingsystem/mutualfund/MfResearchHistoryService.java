package in.aitradingsystem.mutualfund;

import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One AMFI history week every 15 minutes when explicitly enabled. Runs
 * independently of customer activity; never generates retrospective buys.
 */
@Component
public class MfResearchHistoryService {
    private final AmfiHistoricalNavSource source;
    private final MfResearchStore store;
    private final boolean enabled;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    public MfResearchHistoryService(AmfiHistoricalNavSource source, MfResearchStore store,
            @Value("${MINTTICK_MF_HISTORY_ENABLED:false}") boolean enabled) {
        this.source=source; this.store=store; this.enabled=enabled;
    }

    @Scheduled(cron="0 */15 * * * *",zone="Asia/Kolkata")
    public void scheduledStep() {
        if (enabled) step();
    }

    public MfResearchStore.Backfill step() {
        var cursor=store.backfill();
        LocalDate from=cursor.nextFromDate();
        LocalDate yesterday=LocalDate.now(INDIA).minusDays(1);
        if (from.isAfter(yesterday)) return store.backfillComplete();
        LocalDate through=from.plusDays(6).isBefore(yesterday) ?
            from.plusDays(6) : yesterday;
        try {
            return store.acceptHistory(source.fetch(from,through),from,through);
        } catch (RuntimeException failure) {
            store.backfillFailed(failure.getMessage());
            return store.backfill();
        }
    }
}
