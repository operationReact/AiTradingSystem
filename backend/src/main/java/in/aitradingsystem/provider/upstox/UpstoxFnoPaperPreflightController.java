package in.aitradingsystem.provider.upstox;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only operator preflight. Reports technical prerequisites, never execution
 * readiness or provider permission grants. No market prices, secrets, provider
 * HTTP calls, user data, contract identity or order mutation.
 *
 * Available even when the V3/PAPER bean flags are OFF. The customer account
 * edge must continue to deny this entire /api/admin namespace.
 */
@RestController
@RequestMapping("/api/admin/market-data/upstox-analytics/fno/paper")
public class UpstoxFnoPaperPreflightController {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record Preflight(
            Instant checkedAt,
            LocalDate indiaDate,
            String technicalStatus,
            boolean tradingApproved,
            boolean analyticsSourceConfigured,
            boolean v3FeedFeatureConfigured,
            boolean paperFeatureConfigured,
            boolean customerDisplayFlagConfigured,
            boolean paperUsageFlagConfigured,
            boolean paperSessionOpen,
            String streamState,
            int freshExchangeTimedQuotes,
            Instant latestFreshExchangeTradeAt,
            List<String> technicalBlockers,
            String independentProviderRightsEvidence,
            String authenticatedPaperBuyCloseEvidence,
            String deviceAcceptanceEvidence) {}

    private final UpstoxAnalyticsSource source;
    private final ObjectProvider<UpstoxFnoV3LiveFeedService> feedProvider;
    private final MarketSessionCalendar calendar;
    private final Environment env;
    private final Clock clock;

    @Autowired
    public UpstoxFnoPaperPreflightController(UpstoxAnalyticsSource source,
            ObjectProvider<UpstoxFnoV3LiveFeedService> feedProvider,
            MarketSessionCalendar calendar, Environment env) {
        this(source, feedProvider, calendar, env, Clock.systemUTC());
    }

    UpstoxFnoPaperPreflightController(UpstoxAnalyticsSource source,
            ObjectProvider<UpstoxFnoV3LiveFeedService> feedProvider,
            MarketSessionCalendar calendar, Environment env, Clock clock) {
        this.source = source;
        this.feedProvider = feedProvider;
        this.calendar = calendar;
        this.env = env;
        this.clock = clock;
    }

    @GetMapping("/preflight")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<Preflight> preflight() {
        Instant now = clock.instant();
        boolean analytics = source.enabled();
        boolean v3Flag = flag("minttick.upstox-fno.v3-feed-enabled");
        boolean paperFlag = flag("minttick.upstox-fno.paper-enabled");
        boolean displayFlag = flag("minttick.upstox-fno.display-rights-verified");
        boolean paperUseFlag = flag("minttick.upstox-fno.paper-usage-rights-verified");
        boolean open = calendar.isInsideCashSession(now);

        UpstoxFnoV3LiveFeedService.Status status = null;
        if (v3Flag) {
            UpstoxFnoV3LiveFeedService feed = feedProvider.getIfAvailable();
            if (feed != null) status = feed.status();
        }
        String state = status == null ? "UNAVAILABLE" : status.state();
        int fresh = status == null ? 0 : Math.max(0, status.freshExchangeTimedQuotes());
        Instant latest = status == null ? null : status.latestFreshExchangeTradeAt();

        List<String> blockers = new ArrayList<>();
        if (!analytics) blockers.add("ANALYTICS_SOURCE_DISABLED");
        if (!v3Flag) blockers.add("V3_FEED_FEATURE_DISABLED");
        if (!paperFlag) blockers.add("PAPER_FEATURE_DISABLED");
        if (!displayFlag) blockers.add("CUSTOMER_DISPLAY_FLAG_NOT_CONFIGURED");
        if (!paperUseFlag) blockers.add("PAPER_USAGE_FLAG_NOT_CONFIGURED");
        if (!open) blockers.add("PAPER_SESSION_CLOSED");
        if (!"CONNECTED".equals(state)) blockers.add("V3_STREAM_NOT_CONNECTED");
        if (fresh == 0 || latest == null) blockers.add("NO_FRESH_EXCHANGE_TIMED_QUOTES");

        // Even an entirely green technical preflight is NEVER a legal data-use
        // attestation, a successful PAPER fill, or customer device acceptance.
        String technical = blockers.isEmpty() ? "TECHNICAL_PREFLIGHT_PASSED"
                : "TECHNICAL_PREFLIGHT_BLOCKED";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Preflight(now, now.atZone(IST).toLocalDate(),
                        technical, false, analytics, v3Flag, paperFlag,
                        displayFlag, paperUseFlag, open, state, fresh, latest,
                        List.copyOf(blockers), "NOT_CHECKED",
                        "NOT_CHECKED", "NOT_CHECKED"));
    }

    private boolean flag(String name) {
        return env.getProperty(name, Boolean.class, false);
    }
}
