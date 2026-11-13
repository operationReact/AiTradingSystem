package in.aitradingsystem.api;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.mind.MindPaperCycleService;
import in.aitradingsystem.user.CustomerIdentityStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Owner-initiated Mind strategy cycle in a funded PAPER room. Never a LIVE broker path. */
@RestController
@Validated
@RequestMapping("/api/customer/trading-rooms/{roomId}/mind/paper-cycles")
public class CustomerMindPaperCycleController {
    private final MindPaperCycleService cycles;
    private final CustomerIdentityStore identities;

    public CustomerMindPaperCycleController(MindPaperCycleService cycles, CustomerIdentityStore identities) {
        this.cycles = cycles;
        this.identities = identities;
    }

    public record CycleRequest(
        @NotNull @Min(1) Long instrumentId,
        @NotNull @Pattern(regexp = "FIVE_MINUTE|FIFTEEN_MINUTE|ONE_HOUR|ONE_DAY") String interval) {}

    @PostMapping
    public ResponseEntity<MindPaperCycleService.CycleResult> run(@PathVariable UUID roomId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CycleRequest input,
            Authentication auth) {
        return noStore(cycles.run(customer(auth), roomId, key, input.instrumentId(),
            HistoricalCandleInterval.valueOf(input.interval())));
    }

    @GetMapping
    public ResponseEntity<List<MindPaperCycleService.Cycle>> recent(@PathVariable UUID roomId,
            Authentication auth) {
        return noStore(cycles.recent(customer(auth), roomId));
    }

    private UUID customer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream()
                    .noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer session required");
        }
        return identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(account -> "ACTIVE".equals(account.status()) && "CUSTOMER".equals(account.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer not linked"));
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
