package in.aitradingsystem.marketdata;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * A completed-session coverage window shared by the read-only diagnostic
 * and the private historical collector. Never treats holiday or in-progress
 * session bars as a full previous-session scan.
 */
@Service
public class UniverseCandleCoverageService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Duration RETRY_COOLDOWN = Duration.ofHours(18);
    private static final int LOOKBACK_DAYS = 14;

    public record Window(LocalDate fromDate, LocalDate toDate,
                         Instant fromInclusive, Instant toExclusive) {}

    private final UniverseCandleCoverageRepository repository;
    private final Clock clock;

    public UniverseCandleCoverageService(UniverseCandleCoverageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Window window() {
        LocalDate lastCompletedDate = clock.instant().atZone(MARKET_ZONE).toLocalDate().minusDays(1);
        LocalDate from = lastCompletedDate.minusDays(LOOKBACK_DAYS - 1);
        return new Window(from, lastCompletedDate,
                from.atStartOfDay(MARKET_ZONE).toInstant(),
                lastCompletedDate.plusDays(1).atStartOfDay(MARKET_ZONE).toInstant());
    }

    /** Source-backed bar counts, not a research quality or live-tick assertion. */
    public UniverseCandleCoverageRepository.Coverage coverage() {
        Window w = window();
        return repository.coverage(w.fromInclusive(), w.toExclusive());
    }

    /** One candidate at a time; failed/empty provider requests get a cooldown. */
    public Optional<Long> nextDue() {
        Window w = window();
        return repository.nextDue(w.fromInclusive(), w.toExclusive(),
                clock.instant().minus(RETRY_COOLDOWN));
    }
}
