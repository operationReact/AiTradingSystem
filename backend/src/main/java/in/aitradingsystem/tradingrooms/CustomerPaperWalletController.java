package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Customer-specific PAPER wallet and immutable cash history.
 * Does not grant order placement, broker access or LIVE activation.
 */
@RestController
@RequestMapping("/api/customer/trading-rooms/{roomId}/paper")
public class CustomerPaperWalletController {
    private final CustomerPaperWalletService service;
    private final CustomerIdentityStore identities;

    public CustomerPaperWalletController(CustomerPaperWalletService service, CustomerIdentityStore identities) {
        this.service = service;
        this.identities = identities;
    }

    @PostMapping("/activate")
    public ResponseEntity<CustomerPaperWalletService.PaperWallet> activate(
            @PathVariable UUID roomId, Authentication authentication) {
        return noStore(service.activate(customer(authentication), roomId));
    }

    @PostMapping("/resume")
    public ResponseEntity<CustomerPaperWalletService.PaperWallet> resume(
            @PathVariable UUID roomId, Authentication authentication) {
        return noStore(service.setPaused(customer(authentication), roomId, false));
    }

    @PostMapping("/pause")
    public ResponseEntity<CustomerPaperWalletService.PaperWallet> pause(
            @PathVariable UUID roomId, Authentication authentication) {
        return noStore(service.setPaused(customer(authentication), roomId, true));
    }

    @GetMapping("/account")
    public ResponseEntity<CustomerPaperWalletService.PaperWallet> account(
            @PathVariable UUID roomId, Authentication authentication) {
        return noStore(service.get(customer(authentication), roomId));
    }

    @GetMapping("/ledger")
    public ResponseEntity<List<CustomerPaperWalletService.CashEvent>> ledger(
            @PathVariable UUID roomId, Authentication authentication) {
        return noStore(service.ledger(customer(authentication), roomId));
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
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Customer not linked"));
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
