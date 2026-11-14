package in.aitradingsystem.provider.upstox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-only, non-persisting F&O source acceptance probe (#1063).
 *
 * Shares the pre-existing Upstox Analytics source, SSM token and protected probe
 * feature gate. Never exposes raw chain quotes, credentials, orders or permissions
 * to retain/replay/train. Not a customer API and not a freshness assertion.
 */
@RestController
@RequestMapping("/api/admin/market-data/upstox-analytics/fno")
@ConditionalOnProperty(prefix = "minttick.upstox-analytics",
        name = "probe-enabled", havingValue = "true")
public final class UpstoxFnoReadinessProbeController {
    private static final String UNDERLYING = "NSE_INDEX|Nifty 50";
    private static final ZoneId MARKET = ZoneId.of("Asia/Kolkata");
    private static final Duration COOLDOWN = Duration.ofMinutes(15);

    public record Probe(String status, String underlyingKey, LocalDate expiry,
                        int validatedContracts, int validatedStrikes, int callsWithContract,
                        int putsWithContract, int rowsWithCompleteGreeks,
                        Instant observedAt, boolean exchangeQuoteTimeVerified,
                        boolean stored, boolean displayRightsVerified,
                        boolean modelTrainingRightsVerified, boolean liveTradingEnabled) {}

    private final UpstoxAnalyticsSource source;
    private final Clock clock;
    private Instant retryAfter = Instant.EPOCH;

    @Autowired
    public UpstoxFnoReadinessProbeController(UpstoxAnalyticsSource source) {
        this(source, Clock.systemUTC());
    }

    UpstoxFnoReadinessProbeController(UpstoxAnalyticsSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    /** POST-only. Caller selects a real near-term expiry; no guessed expiry rollover. */
    @PostMapping("/probe")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public synchronized ResponseEntity<Probe> probe(
            @RequestParam("expiry") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate expiry) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(MARKET).toLocalDate();
        if (expiry == null || expiry.isBefore(today) || expiry.isAfter(today.plusDays(90))) {
            return result("INVALID_EXPIRY", expiry, 0, 0, 0, 0, 0, now);
        }
        if (!source.enabled()) {
            return result("DISABLED", expiry, 0, 0, 0, 0, 0, now);
        }
        if (now.isBefore(retryAfter)) {
            return result("COOLDOWN", expiry, 0, 0, 0, 0, 0, now);
        }
        // Reserve budget before the first provider read, including failures.
        retryAfter = now.plus(COOLDOWN);
        try {
            List<UpstoxAnalyticsSource.FnoOptionContract> contracts =
                    source.currentFnoOptionContracts(UNDERLYING, expiry);
            UpstoxAnalyticsSource.OptionChainSnapshot chain =
                    source.currentFnoOptionChain(UNDERLYING, expiry);
            if (!"UPSTOX_ANALYTICS".equals(chain.source()) || chain.retrievedAt() == null
                    || chain.exchangeQuoteTimeVerified()) {
                return result("INVALID_PROVENANCE", expiry, 0, 0, 0, 0, 0, now);
            }

            Set<String> ceKeys = new HashSet<>();
            Set<String> peKeys = new HashSet<>();
            for (var contract : contracts) {
                if (!UNDERLYING.equals(contract.underlyingKey())
                        || !expiry.equals(contract.expiry())) {
                    return result("INVALID_PROVENANCE", expiry, 0, 0, 0, 0, 0, now);
                }
                ("CE".equals(contract.side()) ? ceKeys : peKeys).add(contract.instrumentKey());
            }

            int calls = 0;
            int puts = 0;
            int greeks = 0;
            for (var strike : chain.strikes()) {
                if (!UNDERLYING.equals(strike.underlyingKey())
                        || !expiry.equals(strike.expiry())) {
                    return result("INVALID_PROVENANCE", expiry, 0, 0, 0, 0, 0, now);
                }
                if (ceKeys.contains(strike.call().instrumentKey())) calls++;
                if (peKeys.contains(strike.put().instrumentKey())) puts++;
                if (completeGreeks(strike.call()) && completeGreeks(strike.put())) greeks++;
            }
            boolean verified = !contracts.isEmpty() && !chain.strikes().isEmpty()
                    && calls == chain.strikes().size() && puts == chain.strikes().size();
            return result(verified ? "READS_VERIFIED" : "PARTIAL_DATA", expiry,
                    contracts.size(), chain.strikes().size(), calls, puts, greeks, now);
        } catch (RuntimeException ignored) {
            // No upstream response, AWS exception, access token or private quote escapes.
            return result("PROVIDER_UNAVAILABLE", expiry, 0, 0, 0, 0, 0, now);
        }
    }

    private static boolean completeGreeks(UpstoxAnalyticsSource.FnoOptionQuote leg) {
        return leg.iv() != null && leg.delta() != null && leg.gamma() != null
                && leg.theta() != null && leg.vega() != null;
    }

    private static ResponseEntity<Probe> result(String status, LocalDate expiry,
                                                int contracts, int strikes, int calls,
                                                int puts, int greeks, Instant observedAt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Probe(status, UNDERLYING, expiry, contracts, strikes, calls, puts,
                        greeks, observedAt, false, false, false, false, false));
    }
}
