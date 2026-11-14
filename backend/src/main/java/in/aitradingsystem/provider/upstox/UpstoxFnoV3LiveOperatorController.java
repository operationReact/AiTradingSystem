package in.aitradingsystem.provider.upstox;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private, explicit operator control for the read-only Upstox V3 F&O pilot.
 * Never expose through account edge or customer APIs; no quote/order actions.
 */
@RestController
@RequestMapping("/api/admin/market-data/upstox-analytics/fno/live")
@ConditionalOnProperty(name = "minttick.upstox-fno.v3-feed-enabled", havingValue = "true")
public class UpstoxFnoV3LiveOperatorController {
    private static final String UNDERLYING = "NSE_INDEX|Nifty 50";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final UpstoxAnalyticsSource source;
    private final UpstoxFnoV3LiveFeedService service;
    private final Clock clock;

    @Autowired
    public UpstoxFnoV3LiveOperatorController(UpstoxAnalyticsSource source,
                                              UpstoxFnoV3LiveFeedService service) {
        this(source, service, Clock.systemUTC());
    }

    UpstoxFnoV3LiveOperatorController(UpstoxAnalyticsSource source,
                                       UpstoxFnoV3LiveFeedService service, Clock clock) {
        this.source = source;
        this.service = service;
        this.clock = clock;
    }

    /**
     * Resolve only actual provider-listed NIFTY contracts for a near-term expiry.
     * Select nearest-to-spot paired CE and PE keys, never arbitrary user keys.
     * Provider current spot is used only for subscription ordering, not execution.
     */
    @PostMapping("/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<UpstoxFnoV3LiveFeedService.Status> start(
            @RequestParam("expiry") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate expiry) {
        LocalDate today = clock.instant().atZone(IST).toLocalDate();
        if (expiry == null || expiry.isBefore(today) || expiry.isAfter(today.plusDays(90))) {
            throw new IllegalArgumentException("UPSTOX_FNO_INVALID_EXPIRY");
        }
        if (!source.enabled()) throw new IllegalStateException("UPSTOX_ANALYTICS_DISABLED");
        List<UpstoxAnalyticsSource.FnoOptionContract> contracts =
                source.currentFnoOptionContracts(UNDERLYING, expiry);
        UpstoxAnalyticsSource.OptionChainSnapshot chain =
                source.currentFnoOptionChain(UNDERLYING, expiry);
        if (!"UPSTOX_ANALYTICS".equals(chain.source()) || chain.exchangeQuoteTimeVerified()
                || chain.strikes().isEmpty() || contracts.isEmpty()) {
            throw new IllegalStateException("UPSTOX_FNO_SOURCE_NOT_VERIFIED");
        }
        Map<String, UpstoxAnalyticsSource.FnoOptionContract> validContracts = new HashMap<>();
        for (var contract : contracts) {
            if (UNDERLYING.equals(contract.underlyingKey())
                    && expiry.equals(contract.expiry())
                    && UpstoxFnoV3FeedCodec.validKey(contract.instrumentKey())) {
                validContracts.put(contract.instrumentKey(), contract);
            }
        }
        BigDecimal spot = chain.strikes().getFirst().underlyingSpotPrice();
        List<UpstoxAnalyticsSource.FnoOptionStrike> closest = new ArrayList<>(chain.strikes());
        closest.sort(Comparator.comparing(x -> x.strikePrice().subtract(spot).abs()));
        List<String> keys = new ArrayList<>();
        for (var strike : closest) {
            if (keys.size() >= UpstoxFnoV3LiveFeedService.MAX_SUBSCRIPTIONS) break;
            var call = validContracts.get(strike.call().instrumentKey());
            var put = validContracts.get(strike.put().instrumentKey());
            if (call == null || put == null
                    || !"CE".equals(call.side()) || !"PE".equals(put.side())
                    || !expiry.equals(strike.expiry()) || !UNDERLYING.equals(strike.underlyingKey())
                    || call.strikePrice().compareTo(strike.strikePrice()) != 0
                    || put.strikePrice().compareTo(strike.strikePrice()) != 0) continue;
            keys.add(call.instrumentKey());
            keys.add(put.instrumentKey());
        }
        if (keys.isEmpty() || Set.copyOf(keys).size() != keys.size()) {
            throw new IllegalStateException("UPSTOX_FNO_NO_MATCHING_CONTRACTS");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore()).body(service.start(keys));
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<UpstoxFnoV3LiveFeedService.Status> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status());
    }

    @PostMapping("/stop")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<UpstoxFnoV3LiveFeedService.Status> stop() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.stop());
    }
}
