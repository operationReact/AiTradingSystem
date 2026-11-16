package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cognito CUSTOMER owner-only F&O LIVE route. Neither the F&O PAPER account
 * nor the SSM Analytics token is ever an order authority.
 *
 * A customer must supply a unique idempotency key and the exact separate
 * LIVE confirmation header on EVERY new real-money order. An app session or
 * selecting a LIVE broker never implicitly authorizes a real transaction.
 */
@RestController
@RequestMapping("/api/customer/trading-rooms/{roomId}/live/fno")
public final class CustomerUpstoxFnoLiveOrderController {
    private final CustomerUpstoxFnoLiveOrderService orders;
    private final CustomerIdentityStore identities;

    public CustomerUpstoxFnoLiveOrderController(
            CustomerUpstoxFnoLiveOrderService orders, CustomerIdentityStore identities) {
        this.orders = orders;
        this.identities = identities;
    }

    public record OrderRequest(
        @NotNull @Pattern(regexp="NSE_FO\\|[A-Za-z0-9._-]{1,64}") String instrumentKey,
        @NotNull LocalDate expiry,
        @NotNull @Pattern(regexp="BUY|SELL") String side,
        @NotNull @Min(1) @Max(1) Integer lots,
        @NotNull @DecimalMin("0.01") @DecimalMax("1000000")
        @Digits(integer=7,fraction=4) BigDecimal limitPrice) {}

    @GetMapping("/orders")
    public ResponseEntity<List<CustomerUpstoxFnoLiveOrderService.Receipt>> orders(
            @PathVariable UUID roomId, Authentication authentication) {
        return noStore(orders.orders(owner(authentication),roomId));
    }

    @GetMapping("/capability")
    public ResponseEntity<CustomerUpstoxFnoLiveOrderService.Capability> capability(
            @PathVariable UUID roomId, Authentication authentication) {
        return noStore(orders.capability(owner(authentication),roomId));
    }

    @PostMapping("/orders")
    public ResponseEntity<CustomerUpstoxFnoLiveOrderService.Receipt> submit(
            @PathVariable UUID roomId,
            @RequestHeader("Idempotency-Key") String idem,
            @RequestHeader(value="X-Confirm-Live-Order",required=false) String confirm,
            @Valid @RequestBody OrderRequest order, Authentication authentication) {
        return noStore(orders.submit(owner(authentication),roomId,idem,
            new CustomerUpstoxFnoLiveOrderService.Request(
                order.instrumentKey(),order.expiry(),order.side(),
                order.lots(),order.limitPrice()),
            "CONFIRM_LIVE_UPSTOX_NFO".equals(confirm)));
    }

    @PostMapping("/orders/{id}/reconcile")
    public ResponseEntity<CustomerUpstoxFnoLiveOrderService.Receipt> reconcile(
            @PathVariable UUID roomId, @PathVariable UUID id,
            Authentication authentication) {
        return noStore(orders.reconcile(owner(authentication),roomId,id));
    }

    private UUID owner(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream().noneMatch(
                    a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer session required");
        }
        return identities.find("cognito",token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a -> "ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Customer not linked"));
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
