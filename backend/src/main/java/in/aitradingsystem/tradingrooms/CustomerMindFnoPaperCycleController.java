package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * One owner-initiated server-side PAPER evaluation tick. No client-specified
 * symbol, side, price, strategy, size, broker, or execution mode is accepted.
 * The service applies independently verified rights and virtual account gates.
 */
@RestController
@RequestMapping("/api/customer/trading-rooms/{roomId}/mind/fno/paper-cycles")
@ConditionalOnProperty(
    name={"minttick.upstox-fno.paper-enabled","minttick.upstox-fno.v3-feed-enabled"},
    havingValue="true")
public final class CustomerMindFnoPaperCycleController {
    private final CustomerMindFnoPaperCycleService cycles;
    private final CustomerIdentityStore identities;

    public CustomerMindFnoPaperCycleController(
            CustomerMindFnoPaperCycleService cycles, CustomerIdentityStore identities) {
        this.cycles=cycles;
        this.identities=identities;
    }

    @PostMapping
    public ResponseEntity<CustomerMindFnoPaperCycleService.CycleResult> run(
            @PathVariable UUID roomId, Authentication authentication) {
        UUID owner=owner(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(cycles.run(owner,roomId,authentication));
    }

    private UUID owner(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream().noneMatch(
                    a->"ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Customer required");
        }
        return identities.find("cognito",token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a->"ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(()->new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,"Customer account not linked"));
    }
}
