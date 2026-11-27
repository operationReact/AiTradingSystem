package in.aitradingsystem.mutualfund;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Official, daily-after-close public NAV research refresh; no money movement. */
@Component
public class MfResearchRefreshService {
    private final AmfiDailyNavSource source;
    private final MfResearchStore store;
    private final boolean enabled;

    public MfResearchRefreshService(AmfiDailyNavSource source, MfResearchStore store,
            @Value("${MINTTICK_MF_ALERTS_ENABLED:false}") boolean enabled) {
        this.source = source; this.store = store; this.enabled = enabled;
    }

    @Scheduled(cron = "0 45 21 * * *", zone = "Asia/Kolkata")
    public void scheduledRefresh() {
        if (enabled) refresh();
    }

    public MfResearchStore.Feed refresh() {
        try {
            store.accept(source.fetch());
        } catch (RuntimeException failure) {
            // Persist only stable error codes, not untrusted network exception text.
            String code = failure.getMessage();
            store.failure(code);
        }
        return store.board().feed();
    }
}
