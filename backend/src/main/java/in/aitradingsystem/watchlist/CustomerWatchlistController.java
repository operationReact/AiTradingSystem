package in.aitradingsystem.watchlist;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.user.CustomerIdentityStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner-scoped watchlist for the customer stock list. Items are resolved against the active
 * instrument catalogue when added and are never accepted from a caller-supplied symbol.
 */
@RestController
@Validated
@RequestMapping("/api/customer/watchlist")
public class CustomerWatchlistController {
    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    private final CustomerIdentityStore identities;
    private final CustomerWatchlistStore store;
    private final InstrumentMasterService instruments;

    public CustomerWatchlistController(CustomerIdentityStore identities, CustomerWatchlistStore store,
                                       InstrumentMasterService instruments) {
        this.identities = identities;
        this.store = store;
        this.instruments = instruments;
    }

    public record AddRequest(@NotNull @Positive Long instrumentId) {}

    public record WatchlistItem(long instrumentId, String exchange, String tradingSymbol, Instant addedAt,
                                String status) {}

    public record WatchlistResponse(List<WatchlistItem> items, int maxItems) {}

    /**
     * Reconciles stored IDs against the active catalogue. A row whose ID no longer maps to the same
     * exchange and symbol is repointed to the current instrument with that symbol. When no unique
     * current instrument exists, the row is returned as UNAVAILABLE so the owner can remove it.
     */
    @GetMapping
    public ResponseEntity<WatchlistResponse> list(Authentication authentication) {
        UUID owner = requireCustomer(authentication);
        List<CustomerWatchlistStore.Item> stored = store.list(owner);
        Map<Long, Instrument> current = instruments.activeInstrumentsByIds(
            stored.stream().map(CustomerWatchlistStore.Item::instrumentId).toList());
        List<WatchlistItem> items = new ArrayList<>();
        for (CustomerWatchlistStore.Item row : stored) {
            Instrument live = current.get(row.instrumentId());
            if (live != null && live.exchange().equals(row.exchange())
                    && live.tradingSymbol().equals(row.tradingSymbol())) {
                items.add(new WatchlistItem(row.instrumentId(), row.exchange(), row.tradingSymbol(),
                    row.addedAt(), STATUS_ACTIVE));
                continue;
            }
            Optional<Instrument> moved = instruments.activeInstrumentBySymbol(row.exchange(), row.tradingSymbol());
            if (moved.isPresent()) {
                store.repoint(owner, row.instrumentId(), moved.get().id());
                items.add(new WatchlistItem(moved.get().id(), row.exchange(), row.tradingSymbol(),
                    row.addedAt(), STATUS_ACTIVE));
            } else {
                items.add(new WatchlistItem(row.instrumentId(), row.exchange(), row.tradingSymbol(),
                    row.addedAt(), STATUS_UNAVAILABLE));
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new WatchlistResponse(items, CustomerWatchlistStore.MAX_ITEMS));
    }

    @PostMapping
    public ResponseEntity<WatchlistItem> add(Authentication authentication,
                                             @Valid @RequestBody AddRequest request) {
        UUID owner = requireCustomer(authentication);
        Instrument instrument = instruments.requireActiveInstrument(request.instrumentId());
        CustomerWatchlistStore.AddOutcome outcome = store.add(owner, instrument);
        if (outcome == CustomerWatchlistStore.AddOutcome.LIMIT_REACHED) {
            throw new WatchlistLimitReachedException();
        }
        WatchlistItem item = new WatchlistItem(instrument.id(), instrument.exchange(),
            instrument.tradingSymbol(), Instant.now(), STATUS_ACTIVE);
        HttpStatus status = outcome == CustomerWatchlistStore.AddOutcome.ADDED
            ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(item);
    }

    @DeleteMapping("/items/{instrumentId}")
    public ResponseEntity<Void> remove(Authentication authentication,
                                       @PathVariable @Positive long instrumentId) {
        UUID owner = requireCustomer(authentication);
        store.remove(owner, instrumentId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private UUID requireCustomer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) ||
                token.getAuthorities().stream().noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a -> "ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    static final class WatchlistLimitReachedException extends RuntimeException {
        WatchlistLimitReachedException() {
            super("Watchlist limit of " + CustomerWatchlistStore.MAX_ITEMS + " items reached");
        }
    }

    @ExceptionHandler(WatchlistLimitReachedException.class)
    ProblemDetail limitReached(WatchlistLimitReachedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage());
        problem.setProperty("code", "WATCHLIST_LIMIT_REACHED");
        return problem;
    }

    @ExceptionHandler(InstrumentMasterException.class)
    ProblemDetail instrumentFailure(InstrumentMasterException ex) {
        HttpStatus status = "INSTRUMENT_NOT_FOUND".equals(ex.code())
            ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty("code", ex.code());
        return problem;
    }
}
