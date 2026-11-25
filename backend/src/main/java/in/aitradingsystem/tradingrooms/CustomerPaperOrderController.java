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

/** Manual-only virtual-cash equity fills; no broker API order endpoint. */
@RestController
@RequestMapping("/api/customer/trading-rooms/{roomId}/paper")
public class CustomerPaperOrderController {
    private final CustomerPaperOrderService orders;
    private final CustomerIdentityStore identities;

    public CustomerPaperOrderController(CustomerPaperOrderService orders,
            CustomerIdentityStore identities) {
        this.orders = orders;
        this.identities = identities;
    }

    public record OrderRequest(
        @NotNull @Min(1) Long instrumentId,
        @NotNull @Pattern(regexp="BUY|SELL") String side,
        @NotNull @Min(1) @Max(10000) Integer quantity,
        @NotNull @DecimalMin("0.01") @DecimalMax("1000000")
            @Digits(integer=7,fraction=4) BigDecimal limitPrice) {}

    @PostMapping("/orders")
    public ResponseEntity<CustomerPaperOrderService.Receipt> submit(
            @PathVariable UUID roomId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody OrderRequest input, Authentication authentication) {
        return noStore(orders.submit(customer(authentication), roomId, key,
            input.instrumentId(), input.side(), input.quantity(), input.limitPrice()));
    }

    @GetMapping("/orders")
    public ResponseEntity<List<CustomerPaperOrderService.Receipt>> orders(
            @PathVariable UUID roomId, Authentication authentication) {
        return noStore(orders.orders(customer(authentication), roomId));
    }

    @GetMapping("/positions")
    public ResponseEntity<List<CustomerPaperOrderService.Position>> positions(
            @PathVariable UUID roomId, Authentication authentication) {
        return noStore(orders.positions(customer(authentication), roomId));
    }

    private UUID customer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream()
                    .noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer session required");
        }
        return identities.find("cognito",token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(account -> "ACTIVE".equals(account.status())
                && "CUSTOMER".equals(account.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Customer not linked"));
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
