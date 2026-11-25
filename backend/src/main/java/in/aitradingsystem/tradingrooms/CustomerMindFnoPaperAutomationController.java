package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated owner-only PAPER room scheduling control. No LIVE access. */
@RestController
@RequestMapping("/api/customer/trading-rooms/{roomId}/mind/fno/paper-automation")
@ConditionalOnProperty(
    name={"minttick.upstox-fno.paper-enabled","minttick.upstox-fno.v3-feed-enabled"},
    havingValue="true")
public final class CustomerMindFnoPaperAutomationController {
    private final CustomerMindFnoPaperAutomationService automation;
    private final CustomerIdentityStore identities;

    public CustomerMindFnoPaperAutomationController(
            CustomerMindFnoPaperAutomationService automation,
            CustomerIdentityStore identities) {
        this.automation=automation; this.identities=identities;
    }

    public record Control(@NotNull Boolean enabled) {}

    @GetMapping
    public ResponseEntity<CustomerMindFnoPaperAutomationService.State> state(
            @PathVariable UUID roomId,Authentication auth) {
        return noStore(automation.state(owner(auth),roomId));
    }

    @PostMapping("/control")
    public ResponseEntity<CustomerMindFnoPaperAutomationService.State> control(
            @PathVariable UUID roomId,@Valid @RequestBody Control control,
            Authentication auth) {
        return noStore(automation.setEnabled(owner(auth),roomId,control.enabled()));
    }

    @GetMapping("/history")
    public ResponseEntity<List<CustomerMindFnoPaperAutomationService.History>> history(
            @PathVariable UUID roomId,Authentication auth) {
        return noStore(automation.history(owner(auth),roomId));
    }

    private UUID owner(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream().noneMatch(
                    a->"ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Customer required");
        }
        return identities.find("cognito",token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a->"ACTIVE".equals(a.status())&&"CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(()->new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,"Customer account not linked"));
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
