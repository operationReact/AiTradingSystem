package in.aitradingsystem.api;

import in.aitradingsystem.mind.MindPaperCycleService;
import in.aitradingsystem.mind.MindPaperPortfolioService;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only virtual PAPER portfolio across the owner's funded rooms. */
@RestController
public class CustomerMindPaperPortfolioController {
    private final MindPaperPortfolioService portfolio;
    private final MindPaperCycleService cycles;
    private final CustomerIdentityStore identities;

    public CustomerMindPaperPortfolioController(MindPaperPortfolioService portfolio,
                                                MindPaperCycleService cycles,
                                                CustomerIdentityStore identities) {
        this.portfolio = portfolio;
        this.cycles = cycles;
        this.identities = identities;
    }

    /** Owner's Mind PAPER decision journal across rooms, with closed-trade performance. */
    @GetMapping("/api/customer/mind/v1/paper-journal")
    public ResponseEntity<MindPaperCycleService.Journal> journal(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit,
            Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cycles.journal(customer(auth), limit));
    }

    @GetMapping("/api/customer/mind/v1/paper-portfolio")
    public ResponseEntity<MindPaperPortfolioService.Portfolio> inspect(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(portfolio.inspect(customer(auth)));
    }

    private UUID customer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream()
                    .noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer session required");
        }
        return identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(account -> "ACTIVE".equals(account.status())
                && "CUSTOMER".equals(account.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Customer not linked"));
    }

}
