package in.aitradingsystem.tradingrooms;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only, operator-authenticated acceptance check for the SAME F&O market
 * assembly method used by the customer app. Exactly one fixed listed contract.
 * Only field-presence bits are returned; no quote, token, account or trade values.
 * A short cooldown bounds authenticated REST quote/source requests.
 */
@RestController
@RequestMapping("/api/admin/market-data/upstox-analytics/fno")
@ConditionalOnProperty(name = "minttick.upstox-fno.display-rights-verified",
        havingValue = "true")
public class AdminFnoCustomerBoardAuditController {
    private static final String CONTRACT = "NSE_FO|47821";
    private static final LocalDate EXPIRY = LocalDate.of(2026, 12, 1);
    private static final Duration COOLDOWN = Duration.ofSeconds(90);
    private static final Duration SNAPSHOT_MAX_AGE = Duration.ofMinutes(5);

    public record Result(String status, String boardStatus,
            boolean displayRightsVerified, boolean discoveryVerified,
            boolean sourceReady, boolean marketOpen, boolean liveOrderEnabled,
            int validatedStrikes, boolean listedContractFound,
            boolean verifiedLastLtpPresent, boolean verifiedPreviousClosePresent,
            boolean verifiedLastOiPresent, boolean verifiedLastTradeTimePresent,
            boolean chainSnapshotLtpPresent, boolean chainSnapshotOiPresent,
            boolean chainSnapshotGreeksComplete, boolean chainSnapshotRecent,
            boolean safeNoOrders) {}

    private final CustomerFnoDiscoveryController discovery;
    private final Clock clock;
    private Instant retryAfter = Instant.EPOCH;

    @Autowired
    public AdminFnoCustomerBoardAuditController(CustomerFnoDiscoveryController discovery) {
        this(discovery, Clock.systemUTC());
    }

    AdminFnoCustomerBoardAuditController(CustomerFnoDiscoveryController discovery, Clock clock) {
        this.discovery = discovery;
        this.clock = clock;
    }

    @GetMapping("/customer-board-audit")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public synchronized ResponseEntity<Result> audit() {
        Instant now = clock.instant();
        var cap = discovery.internalCapability();
        if (!cap.displayRightsVerified() || !cap.sourceReady()) {
            return noStore(empty(cap, "SOURCE_OR_RIGHTS_UNAVAILABLE"));
        }
        if (now.isBefore(retryAfter)) {
            return noStore(empty(cap, "COOLDOWN"));
        }
        retryAfter = now.plus(COOLDOWN);
        try {
            var board = discovery.internalMarket(EXPIRY);
            return noStore(summarize(cap, board, now));
        } catch (ResponseStatusException e) {
            return noStore(empty(cap, "MARKET_HTTP_" + e.getStatusCode().value()));
        } catch (RuntimeException e) {
            // Never leak upstream provider details, account identifiers or quotes.
            return noStore(empty(cap, "MARKET_UNAVAILABLE"));
        }
    }

    static Result summarize(CustomerFnoDiscoveryController.Capability cap,
            CustomerPaperFnoMarketController.Market board, Instant now) {
        var contracts = board.strikes() == null ? java.util.List
            .<CustomerPaperFnoMarketController.Strike>of() : board.strikes();
        var leg = contracts.stream()
            .flatMap(strike -> java.util.stream.Stream.of(strike.call(), strike.put()))
            .filter(Objects::nonNull)
            .filter(candidate -> CONTRACT.equals(candidate.instrumentKey()))
            .findFirst().orElse(null);
        var indicators = board.chainSnapshotIndicators();
        var chain = indicators == null ? null : indicators.get(CONTRACT);
        Instant captured = board.snapshotAt();
        boolean recent = captured != null && !captured.isAfter(now)
                && Duration.between(captured, now).compareTo(SNAPSHOT_MAX_AGE) <= 0;
        boolean found = leg != null;
        boolean noOrders = !board.liveOrderEnabled();
        String status = !noOrders ? "UNEXPECTED_ORDER_AUTHORITY" :
            !found ? "CONTRACT_NOT_IN_BOARD" : "CONTRACT_MATCHED";
        String boardStatus = switch (board.status()) {
            case "MARKET_CLOSED", "NO_FRESH_QUOTES", "FRESH_QUOTES" -> board.status();
            default -> "OTHER";
        };
        return new Result(status, boardStatus, cap.displayRightsVerified(),
            cap.contractDiscoveryVerified(), cap.sourceReady(), cap.marketOpen(),
            board.liveOrderEnabled(), Math.min(contracts.size(), 1024), found,
            found && positive(leg.ltp()),
            found && positive(leg.previousClose()),
            found && validOi(leg.openInterest()),
            found && leg.exchangeAt() != null,
            recent && chain != null && positive(chain.ltp()),
            recent && chain != null && validOi(chain.openInterest()),
            recent && chain != null && chain.iv() != null &&
                chain.delta() != null && chain.gamma() != null &&
                chain.theta() != null && chain.vega() != null,
            recent && chain != null, noOrders);
    }

    private static boolean positive(java.math.BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean validOi(Long value) {
        return value != null && value >= 0;
    }

    private static Result empty(CustomerFnoDiscoveryController.Capability cap, String state) {
        return new Result(state, "UNAVAILABLE", cap.displayRightsVerified(),
            cap.contractDiscoveryVerified(), cap.sourceReady(), cap.marketOpen(),
            false, 0, false, false, false, false, false,
            false, false, false, false, true);
    }

    private static ResponseEntity<Result> noStore(Result result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
