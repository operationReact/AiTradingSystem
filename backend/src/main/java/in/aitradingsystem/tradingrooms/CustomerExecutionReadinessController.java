package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
import in.aitradingsystem.instrument.InstrumentMasterException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Both modes can inspect execution readiness and validate order intent.
 * No LIVE order submission route exists; preflight never trades. An explicit
 * broker-selection POST writes a room preference, not an order or grant.
 */
@RestController
@RequestMapping("/api/customer/trading-rooms/{roomId}")
public class CustomerExecutionReadinessController {
    private final CustomerExecutionReadinessService service;
    private final CustomerIdentityStore identities;

    public CustomerExecutionReadinessController(
            CustomerExecutionReadinessService service, CustomerIdentityStore identities) {
        this.service = service;
        this.identities = identities;
    }

    public record LiveBrokerSelectionRequest(
        @NotNull @Pattern(regexp = "ANGEL_ONE|UPSTOX") String broker) {}

    public record OrderPreflightRequest(
        @NotNull @Min(1) Long instrumentId,
        @NotNull @Pattern(regexp = "BUY|SELL") String side,
        @NotNull @Min(1) @Max(10000) Integer quantity,
        @NotNull @DecimalMin("0.01") @DecimalMax("10000000") @Digits(integer=8, fraction=2)
            BigDecimal limitPrice) {}

    @GetMapping("/execution/readiness")
    public ResponseEntity<CustomerExecutionReadinessService.Readiness> readiness(
            @PathVariable UUID roomId, Authentication authentication) {
        return noStore(service.readiness(customer(authentication), roomId));
    }

    @PostMapping("/execution/broker-selection")
    public ResponseEntity<CustomerExecutionReadinessService.Readiness> chooseBroker(
            @PathVariable UUID roomId,
            @Valid @RequestBody LiveBrokerSelectionRequest input,
            Authentication authentication) {
        return noStore(service.selectLiveBroker(
            customer(authentication), roomId, input.broker()));
    }

    @PostMapping("/orders/preflight")
    public ResponseEntity<CustomerExecutionReadinessService.Preflight> preflight(
            @PathVariable UUID roomId,
            @Valid @RequestBody OrderPreflightRequest input,
            Authentication authentication) {
        return noStore(service.preflight(customer(authentication), roomId,
            input.instrumentId(), input.side(), input.quantity(), input.limitPrice()));
    }

    @ExceptionHandler(InstrumentMasterException.class)
    ProblemDetail instrumentRejected(InstrumentMasterException ex) {
        HttpStatus status = "INSTRUMENT_NOT_FOUND".equals(ex.code())
            ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        detail.setProperty("code", ex.code());
        return detail;
    }

    private UUID customer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream()
                    .noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer session required");
        }
        return identities.find("cognito", token.getToken().getIssuer().toString(),
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
