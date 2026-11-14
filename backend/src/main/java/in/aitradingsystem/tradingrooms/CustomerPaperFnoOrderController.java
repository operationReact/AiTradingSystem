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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Feature-gated manual PAPER options; no broker/live order endpoint. */
@RestController
@RequestMapping("/api/customer/trading-rooms/{roomId}/paper/fno")
@ConditionalOnProperty(name="minttick.upstox-fno.paper-enabled", havingValue="true")
public class CustomerPaperFnoOrderController {
    private final CustomerPaperFnoOrderService orders;
    private final CustomerIdentityStore identities;

    public CustomerPaperFnoOrderController(CustomerPaperFnoOrderService orders,
                                           CustomerIdentityStore identities) {
        this.orders=orders;
        this.identities=identities;
    }

    public record OrderRequest(
        @NotNull @Pattern(regexp="NSE_FO\\|[A-Za-z0-9._-]{1,64}")
            String instrumentKey,
        @NotNull LocalDate expiry,
        @NotNull @Pattern(regexp="BUY|SELL") String side,
        @NotNull @Min(1) @Max(20) Integer lots,
        @NotNull @DecimalMin("0.01") @DecimalMax("1000000")
        @Digits(integer=7,fraction=4) BigDecimal limitPrice) {}

    @PostMapping("/orders")
    public ResponseEntity<CustomerPaperFnoOrderService.Fill> submit(
            @PathVariable UUID roomId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody OrderRequest order, Authentication authentication) {
        return noStore(orders.submit(owner(authentication),roomId,key,
                order.instrumentKey(),order.expiry(),order.side(),
                order.lots(),order.limitPrice()));
    }

    @GetMapping("/orders")
    public ResponseEntity<List<CustomerPaperFnoOrderService.Fill>> orders(
            @PathVariable UUID roomId, Authentication authentication) {
        return noStore(orders.orders(owner(authentication),roomId));
    }

    @GetMapping("/positions")
    public ResponseEntity<List<CustomerPaperFnoOrderService.Position>> positions(
            @PathVariable UUID roomId, Authentication authentication) {
        return noStore(orders.positions(owner(authentication),roomId));
    }

    private UUID owner(Authentication authentication) {
        if(!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream()
                    .noneMatch(a->"ROLE_CUSTOMER".equals(a.getAuthority())))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Customer session required");
        return identities.find("cognito",token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(account->"ACTIVE".equals(account.status())
                    && "CUSTOMER".equals(account.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Customer not linked"));
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
