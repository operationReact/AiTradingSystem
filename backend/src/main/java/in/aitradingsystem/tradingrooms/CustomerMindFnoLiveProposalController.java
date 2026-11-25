package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only LIVE NFO proposal. No POST, broker order, confirmation shortcut,
 * idempotency key, approval or persistent execution permission exists here.
 */
@RestController
@RequestMapping("/api/customer/trading-rooms/{roomId}/mind/fno/live-proposal")
public final class CustomerMindFnoLiveProposalController {
    private final CustomerMindFnoLiveProposalService proposals;
    private final CustomerIdentityStore identities;

    public CustomerMindFnoLiveProposalController(CustomerMindFnoLiveProposalService proposals,
            CustomerIdentityStore identities) {
        this.proposals=proposals;
        this.identities=identities;
    }

    @GetMapping
    public ResponseEntity<CustomerMindFnoLiveProposalService.Proposal> inspect(
            @PathVariable UUID roomId,Authentication authentication) {
        UUID owner=owner(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(proposals.inspect(owner,roomId,authentication));
    }

    private UUID owner(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream().noneMatch(
                    a->"ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Customer session required");
        }
        return identities.find("cognito",token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a->"ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(()->new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,"Customer account not linked"));
    }
}
