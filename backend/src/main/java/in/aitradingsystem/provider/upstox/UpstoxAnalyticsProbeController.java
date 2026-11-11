package in.aitradingsystem.provider.upstox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Non-persisting, internal operator acceptance probe. Never exposes user secrets or model signals. */
@RestController
@RequestMapping("/api/admin/market-data/upstox-analytics")
@ConditionalOnProperty(prefix = "minttick.upstox-analytics", name = "probe-enabled", havingValue = "true")
public final class UpstoxAnalyticsProbeController {
    private static final ZoneId MARKET = ZoneId.of("Asia/Kolkata");
    private static final Duration COOLDOWN = Duration.ofMinutes(15);

    public record Probe(String status, int validatedDailyCandles, int currentRatios,
                        boolean stored, boolean modelTrainingRightsVerified,
                        boolean liveTradingEnabled) {}

    private final UpstoxAnalyticsSource source;
    private final Clock clock;
    private Instant retryAfter = Instant.EPOCH;

    @Autowired
    public UpstoxAnalyticsProbeController(UpstoxAnalyticsSource source) {
        this(source, Clock.systemUTC());
    }

    UpstoxAnalyticsProbeController(UpstoxAnalyticsSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    @PostMapping("/probe")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public synchronized ResponseEntity<Probe> probe() {
        Instant now = clock.instant();
        if (!source.enabled()) return result("DISABLED", 0, 0);
        if (now.isBefore(retryAfter)) return result("COOLDOWN", 0, 0);
        retryAfter = now.plus(COOLDOWN); // Even a failed auth consumes the budget.
        try {
            LocalDate lastDay = now.atZone(MARKET).toLocalDate().minusDays(1);
            int candles = source.dailyEquityCandles(
                    "NSE_EQ|INE002A01018", lastDay.minusDays(6), lastDay).size();
            int ratios = source.currentRatios("INE002A01018").size();
            return result(candles > 0 && ratios > 0 ? "READS_VERIFIED" : "PARTIAL_DATA",
                    candles, ratios);
        } catch (RuntimeException ignored) {
            // No provider error body or SSM exception leaks into response/logs.
            return result("PROVIDER_UNAVAILABLE", 0, 0);
        }
    }

    private static ResponseEntity<Probe> result(String status, int bars, int ratios) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Probe(status, bars, ratios, false, false, false));
    }
}
