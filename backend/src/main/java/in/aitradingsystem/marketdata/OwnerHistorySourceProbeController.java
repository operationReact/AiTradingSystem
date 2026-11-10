package in.aitradingsystem.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.AngelOneException;
import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One source-authenticated, non-persisting readiness sample for the private
 * owner feed. Neither this endpoint nor technical API success establishes
 * market-data redistribution rights, live trading, or safe bulk backfill.
 */
@RestController
@RequestMapping("/api/market-data/candles/owner-source-probe")
@ConditionalOnProperty(prefix = "minttick.owner-feed", name = "enabled", havingValue = "true")
public class OwnerHistorySourceProbeController {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter PROVIDER_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");
    private static final Duration COOLDOWN = Duration.ofMinutes(15);
    private static final int MAX_ROWS = 100;

    public record Probe(String status, int validatedRows, boolean stored,
                        boolean redistributionRightsVerified, boolean tradingEnabled) {}

    private final InstrumentMasterService instruments;
    private final MarketSessionCalendar calendar;
    private final AppAngelOneMarketFeedService feed;
    private final HistoricalCandleParser parser;
    private final Clock clock;
    private Instant retryAfter = Instant.EPOCH;

    public OwnerHistorySourceProbeController(InstrumentMasterService instruments,
            MarketSessionCalendar calendar, AppAngelOneMarketFeedService feed,
            HistoricalCandleParser parser, Clock clock) {
        this.instruments = instruments;
        this.calendar = calendar;
        this.feed = feed;
        this.parser = parser;
        this.clock = clock;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public synchronized ResponseEntity<Probe> probe() {
        var result = sample();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    private Probe sample() {
        Instant now = clock.instant();
        if (now.isBefore(retryAfter)) return response("COOLDOWN", 0);
        if (!feed.available()) return response("OWNER_FEED_UNAVAILABLE", 0);
        Optional<in.aitradingsystem.instrument.Instrument> equity =
                instruments.activeInstrumentBySymbol("NSE", "RELIANCE-EQ");
        if (equity.isEmpty()) return response("SAMPLE_INSTRUMENT_MISSING", 0);

        LocalDate today = now.atZone(MARKET_ZONE).toLocalDate();
        LocalDate sessionDate = null;
        for (int daysBack = 1; daysBack <= 14; daysBack++) {
            LocalDate candidate = today.minusDays(daysBack);
            if (calendar.isTradingDay(candidate)) {
                sessionDate = candidate;
                break;
            }
        }
        if (sessionDate == null) return response("NO_COMPLETED_SESSION", 0);
        // Set cooldown before any provider operation to bound repeated attempts,
        // including invalid/empty responses and failed authentications.
        retryAfter = now.plus(COOLDOWN);
        try {
            String from = LocalDateTime.of(sessionDate, LocalTime.of(9, 15))
                    .format(PROVIDER_DATE);
            String to = LocalDateTime.of(sessionDate, LocalTime.of(15, 30))
                    .format(PROVIDER_DATE);
            JsonNode raw = feed.historicalCandles("NSE", equity.get().symbolToken(),
                    "FIVE_MINUTE", from, to);
            if (raw == null || !raw.isArray() || raw.size() > MAX_ROWS) {
                return response("INVALID_PROVIDER_RESPONSE", 0);
            }
            var candles = parser.parse(raw);
            if (candles.isEmpty()) return response("NO_PROVIDER_CANDLES", 0);
            LocalDate requested = sessionDate;
            if (candles.stream().anyMatch(c ->
                    !c.candleTime().atZone(MARKET_ZONE).toLocalDate().equals(requested))) {
                return response("INVALID_PROVIDER_RESPONSE", 0);
            }
            return response("VALID_CANDLES", candles.size());
        } catch (AngelOneException failure) {
            return response(switch (failure.kind()) {
                case RATE_LIMITED -> "PROVIDER_RATE_LIMITED";
                case REJECTED -> "PROVIDER_REJECTED";
                case NOT_CONFIGURED -> "OWNER_FEED_UNAVAILABLE";
                default -> "PROVIDER_UNAVAILABLE";
            }, 0);
        } catch (RuntimeException failure) {
            // Never reveal sessions, tokens, raw error bodies, or candle values.
            return response("PROVIDER_UNAVAILABLE", 0);
        }
    }

    private static Probe response(String status, int rows) {
        return new Probe(status, rows, false, false, false);
    }
}
