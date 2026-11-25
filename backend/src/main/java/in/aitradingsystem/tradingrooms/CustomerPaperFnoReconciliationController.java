package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
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
 * Customer-authenticated PAPER-only forensic receipt; no prices, source reads,
 * broker order actions or changes to the shared virtual wallet.
 */
@RestController
@RequestMapping("/api/customer/trading-rooms/{roomId}/paper/fno")
public class CustomerPaperFnoReconciliationController {
    private final CustomerPaperFnoReconciliationService reconciliation;
    private final CustomerPaperSharedWalletAuditService walletAudit;
    private final CustomerIdentityStore identities;
    private final JdbcClient jdbc;

    public CustomerPaperFnoReconciliationController(
            CustomerPaperFnoReconciliationService reconciliation,
            CustomerPaperSharedWalletAuditService walletAudit,
            CustomerIdentityStore identities, JdbcClient jdbc) {
        this.reconciliation=reconciliation;
        this.walletAudit=walletAudit;
        this.identities=identities;
        this.jdbc=jdbc;
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<CustomerPaperFnoReconciliationService.Receipt> receipt(
            @PathVariable UUID roomId, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reconciliation.inspect(verifiedOwner(authentication, roomId),roomId));
    }

    /** Cash/P&L only; stock and option positions need separate verification. */
    @GetMapping("/wallet-reconciliation")
    public ResponseEntity<CustomerPaperSharedWalletAuditService.Audit> walletReconciliation(
            @PathVariable UUID roomId, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(walletAudit.inspect(verifiedOwner(authentication, roomId),roomId));
    }

    private UUID verifiedOwner(Authentication authentication, UUID roomId) {
        UUID owner = owner(authentication);
        boolean allowed = jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM customer_trading_room
                WHERE id=:room AND user_id=:owner AND mode='PAPER'
                  AND status='PAPER_FUNDED' AND market_scope IN ('FNO','ALL'))
            """).param("room", roomId).param("owner", owner)
            .query(Boolean.class).single();
        if (!allowed) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Funded PAPER F&O room not found");
        return owner;
    }

    private UUID owner(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream()
                    .noneMatch(a->"ROLE_CUSTOMER".equals(a.getAuthority())))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Customer session required");
        return identities.find("cognito",token.getToken().getIssuer().toString(),
                    token.getToken().getSubject())
            .filter(a->"ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Customer not linked"));
    }
}
