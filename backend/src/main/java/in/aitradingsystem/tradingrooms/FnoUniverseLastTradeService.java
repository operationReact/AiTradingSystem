package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Bounded on-demand source last-trade snapshot for any verified NSE_FO key.
 * It is NEVER a V3 streaming subscription, order-depth quote, or authority
 * to trade. A REST last price can be from an earlier session; exchange time
 * and observed time remain distinct and source-original.
 */
@Service
public final class FnoUniverseLastTradeService {
    private static final int MAX_CACHE_ENTRIES = 256;
    private static final int MAX_SOURCE_CALLS_PER_MINUTE = 30;
    private static final Duration IN_SESSION_TTL = Duration.ofSeconds(5);
    private static final Duration OFF_SESSION_TTL = Duration.ofMinutes(1);

    private final FnoFuturesContractRegistry registry;
    private final UpstoxAnalyticsSource source;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean displayRights;
    private final boolean restLastPriceRights;
    private final LinkedHashMap<String, Cache> lastByKey =
        new LinkedHashMap<>(MAX_CACHE_ENTRIES, .75f, true);
    private final ArrayDeque<Instant> sourceCalls = new ArrayDeque<>();

    private record Cache(Instant fetchedAt, LastTrade lastTrade) {}

    public record LastTrade(String instrumentKey, String status,
            String provenance, BigDecimal ltp, BigDecimal previousClose,
            Long openInterest, Instant exchangeAt, Instant observedAt,
            boolean freshExchangeTime, boolean executable,
            Instant checkedAt) {}

    @Autowired
    public FnoUniverseLastTradeService(FnoFuturesContractRegistry registry,
            UpstoxAnalyticsSource source, MarketSessionCalendar calendar,
            @Value("${minttick.upstox-fno.display-rights-verified:false}")
            boolean displayRights,
            @Value("${minttick.upstox-fno.rest-last-price-display-rights-verified:false}")
            boolean restLastPriceRights) {
        this(registry, source, calendar, Clock.systemUTC(),
            displayRights, restLastPriceRights);
    }

    FnoUniverseLastTradeService(FnoFuturesContractRegistry registry,
            UpstoxAnalyticsSource source, MarketSessionCalendar calendar,
            Clock clock, boolean displayRights, boolean restLastPriceRights) {
        this.registry = registry;
        this.source = source;
        this.calendar = calendar;
        this.clock = clock;
        this.displayRights = displayRights;
        this.restLastPriceRights = restLastPriceRights;
    }

    public synchronized LastTrade read(String key) {
        Instant now = clock.instant();
        if (key == null || !key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}"))
            return blocked(key, "INVALID_INSTRUMENT_KEY", now);
        if (registry.listedInstrument(key).isEmpty())
            return blocked(key, "INSTRUMENT_NOT_VERIFIED_IN_CURRENT_MASTER", now);
        if (!displayRights || !restLastPriceRights)
            return blocked(key, "LAST_PRICE_DISPLAY_RIGHTS_NOT_VERIFIED", now);
        if (!source.enabled())
            return blocked(key, "LAST_PRICE_SOURCE_UNAVAILABLE", now);
        boolean open = calendar.isInsideFnoSession(now);
        Duration ttl = open ? IN_SESSION_TTL : OFF_SESSION_TTL;
        Cache cached = lastByKey.get(key);
        if (cached != null && !now.isBefore(cached.fetchedAt()) &&
                now.isBefore(cached.fetchedAt().plus(ttl))) {
            return cached.lastTrade();
        }
        lastByKey.remove(key);
        while (!sourceCalls.isEmpty() &&
                !now.isBefore(sourceCalls.peekFirst().plusSeconds(60))) {
            sourceCalls.removeFirst();
        }
        if (sourceCalls.size() >= MAX_SOURCE_CALLS_PER_MINUTE)
            return blocked(key, "SOURCE_RATE_BUDGET_EXHAUSTED", now);
        sourceCalls.addLast(now);
        LastTrade result;
        try {
            var values = source.listedFnoLastTrades(List.of(key));
            var quote = values == null ? null : values.get(key);
            if (quote == null || quote.ltp() == null ||
                    quote.ltp().signum() <= 0 ||
                    quote.exchangeAt() == null || quote.observedAt() == null ||
                    quote.exchangeAt().isAfter(now.plusSeconds(2)) ||
                    quote.observedAt().isAfter(now.plusSeconds(10)) ||
                    quote.exchangeAt().isBefore(now.minus(Duration.ofDays(7))) ||
                    quote.observedAt().isBefore(quote.exchangeAt())) {
                result = blocked(key, "LAST_TRADE_NOT_AVAILABLE", now);
            } else {
                // REST is never an executable bid/ask. Even a current-session
                // last trade can be stale or lack quote-side liquidity.
                boolean fresh = open &&
                    !quote.exchangeAt().isBefore(now.minusSeconds(15)) &&
                    !quote.observedAt().isBefore(now.minusSeconds(15));
                result = new LastTrade(key, "SOURCE_LAST_TRADE",
                    "UPSTOX_REST_V3_LAST_TRADE", quote.ltp(),
                    quote.previousClose(), quote.openInterest(),
                    quote.exchangeAt(), quote.observedAt(),
                    fresh, false, now);
            }
        } catch (RuntimeException unavailable) {
            result = blocked(key, "LAST_PRICE_SOURCE_UNAVAILABLE", now);
        }
        lastByKey.put(key, new Cache(now, result));
        if (lastByKey.size() > MAX_CACHE_ENTRIES)
            lastByKey.remove(lastByKey.keySet().iterator().next());
        return result;
    }

    private static LastTrade blocked(String key, String status, Instant now) {
        return new LastTrade(key, status, null, null, null,
            null, null, null, false, false, now);
    }
}
