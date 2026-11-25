package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Strictly read-only, ephemeral Upstox-backed listing for the existing F&O
 * customer expiry dropdown. No fabricated dates, REST LTPs or provider
 * credentials are exposed. The customer controller additionally checks room
 * ownership and verified display rights before invoking this bean.
 */
@Component
@Primary
public final class UpstoxCustomerFnoExpiryCatalog implements CustomerFnoExpiryCatalog {
    private static final Duration SUCCESS_TTL = Duration.ofSeconds(60);
    private static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(30);

    private final UpstoxAnalyticsSource source;
    /**
     * Expiry listing is permitted by EITHER the explicit contract-discovery
     * permission OR the display permission (which implies it). Expiry dates
     * carry no prices, so they are gated separately from live-price display.
     */
    private final boolean discoveryPermitted;
    private Listing cached;
    private Instant refreshAfter;

    @Autowired
    public UpstoxCustomerFnoExpiryCatalog(UpstoxAnalyticsSource source,
            @Value("${minttick.upstox-fno.display-rights-verified:false}")
            boolean displayRightsVerified,
            @Value("${minttick.upstox-fno.contract-discovery-verified:false}")
            boolean contractDiscoveryVerified) {
        this.source = source;
        this.discoveryPermitted = displayRightsVerified || contractDiscoveryVerified;
    }

    /** Display-rights-only form; contract discovery follows display rights. */
    public UpstoxCustomerFnoExpiryCatalog(UpstoxAnalyticsSource source,
            boolean displayRightsVerified) {
        this(source, displayRightsVerified, false);
    }

    @Override
    public synchronized Listing listing(Instant now) {
        if (now == null || !discoveryPermitted || !source.enabled())
            return blocked("BLOCKED_NO_PERMITTED_SOURCE");
        if (cached != null && refreshAfter != null
                && !now.isBefore(refreshAfter.minus(SUCCESS_TTL))
                && now.isBefore(refreshAfter)) {
            return cached;
        }
        try {
            List<LocalDate> dates = source.currentNiftyOptionExpiries(now);
            if (dates.isEmpty()) {
                cached = blocked("NO_LISTED_EXPIRIES");
                refreshAfter = now.plus(FAILURE_COOLDOWN);
            } else {
                cached = new Listing(Listing.VERIFIED,now,dates);
                refreshAfter = now.plus(SUCCESS_TTL);
            }
        } catch (RuntimeException unavailable) {
            // Never return stale listing as currently VERIFIED. No provider
            // message, request URL, body, token or raw contract key leaks.
            cached = blocked("BLOCKED_SOURCE_UNAVAILABLE");
            refreshAfter = now.plus(FAILURE_COOLDOWN);
        }
        return cached;
    }

    private static Listing blocked(String reason) {
        return new Listing(reason,null,List.of());
    }
}
