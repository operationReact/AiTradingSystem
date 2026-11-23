package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Contract-level original-time input gate for Mintick Mind and read-only
 * customer diagnostics. A complete BOD instrument catalog and an active
 * WebSocket are NEVER substitutes for an eligible fresh exchange-time tick.
 *
 * This provides evidence, not an automatic decision, trading grant or order.
 */
@Service
public final class FnoUniverseCoverageService {
    private final FnoFuturesContractRegistry registry;
    private final ObjectProvider<UpstoxFnoV3LiveFeedService> feed;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean displayRights;

    public record Evidence(String instrumentKey, String state,
            boolean catalogListed, boolean subscribed, boolean freshLtp,
            boolean depthQualified, boolean orderAuthorized,
            boolean hasOpenInterest, boolean hasVolume, boolean hasGreeks,
            Instant exchangeAt, Instant observedAt, Instant checkedAt) {}

    @Autowired
    public FnoUniverseCoverageService(FnoFuturesContractRegistry registry,
            ObjectProvider<UpstoxFnoV3LiveFeedService> feed,
            MarketSessionCalendar calendar,
            @Value("${minttick.upstox-fno.display-rights-verified:false}")
            boolean displayRights) {
        this(registry, feed, calendar, Clock.systemUTC(), displayRights);
    }

    FnoUniverseCoverageService(FnoFuturesContractRegistry registry,
            ObjectProvider<UpstoxFnoV3LiveFeedService> feed,
            MarketSessionCalendar calendar, Clock clock, boolean displayRights) {
        this.registry = registry;
        this.feed = feed;
        this.calendar = calendar;
        this.clock = clock;
        this.displayRights = displayRights;
    }

    public Evidence inspect(String instrumentKey) {
        Instant now = clock.instant();
        if (instrumentKey == null ||
                !instrumentKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}"))
            return absent(instrumentKey, "INVALID_INSTRUMENT_KEY", now);
        if (registry.listedInstrument(instrumentKey).isEmpty()) {
            String state = registry.universeStatus().state();
            return absent(instrumentKey,
                "LOADED".equals(state) || "STALE_LAST_REFRESH_FAILED".equals(state)
                    ? "NOT_IN_CURRENT_LISTED_UNIVERSE" : "CATALOG_UNAVAILABLE", now);
        }
        if (!displayRights)
            return blocked(instrumentKey, "DISPLAY_RIGHTS_NOT_VERIFIED", now);
        if (!calendar.isInsideFnoSession(now))
            return blocked(instrumentKey, "MARKET_CLOSED", now);
        var live = feed.getIfAvailable();
        if (live == null)
            return blocked(instrumentKey, "V3_FEED_NOT_CONFIGURED", now);
        if (!"CONNECTED".equals(live.status().state()))
            return blocked(instrumentKey, "V3_FEED_NOT_CONNECTED", now);
        if (!live.hasActiveFnoSubscription(instrumentKey))
            return blocked(instrumentKey, "NOT_SUBSCRIBED", now);
        Optional<UpstoxFnoV3LiveFeedService.DisplayQuote> display =
            live.eligibleDisplayQuote(instrumentKey);
        if (display.isEmpty())
            return new Evidence(instrumentKey, "SUBSCRIBED_NO_FRESH_TRADE",
                true, true, false, false, false,
                false, false, false, null, null, now);
        var quote = display.orElseThrow();
        boolean depth = live.eligiblePaperQuote(instrumentKey).isPresent();
        return new Evidence(instrumentKey,
            depth ? "FRESH_V3_LTP_AND_DEPTH" : "FRESH_V3_LTP_ONLY",
            true, true, true, depth, false,
            quote.openInterest() != null, quote.volume() != null,
            quote.iv() != null && quote.delta() != null &&
                quote.gamma() != null && quote.theta() != null &&
                quote.vega() != null,
            quote.exchangeAt(), quote.observedAt(), now);
    }

    private static Evidence absent(String key, String state, Instant now) {
        return new Evidence(key, state, false, false,
            false, false, false, false, false, false, null, null, now);
    }

    private static Evidence blocked(String key, String state, Instant now) {
        return new Evidence(key, state, true, false,
            false, false, false, false, false, false, null, null, now);
    }
}
