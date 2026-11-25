package in.aitradingsystem.paper;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduler exists only when explicitly enabled. */
@Component
@ConditionalOnProperty(prefix = "app.paper.intraday-close", name = "enabled", havingValue = "true")
public class IntradayPaperCloseScheduler {
    private static final Logger log = LoggerFactory.getLogger(IntradayPaperCloseScheduler.class);

    private final IntradayPaperCloseService service;
    private final Clock clock;

    public IntradayPaperCloseScheduler(IntradayPaperCloseService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.paper.intraday-close.poll-ms:60000}")
    public void run() {
        var result = service.closeEligiblePositions(clock.instant());
        if (result.positionsFound() > 0) {
            log.info("Intraday PAPER close run tradeDate={} positions={} filled={} replayedOrRejected={}",
                result.tradeDate(), result.positionsFound(), result.filled(), result.replayedOrRejected());
        }
    }
}
