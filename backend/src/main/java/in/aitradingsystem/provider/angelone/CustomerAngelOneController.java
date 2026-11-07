package in.aitradingsystem.provider.angelone;

import in.aitradingsystem.user.CustomerIdentityStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only per-customer Angel One connection.
 * Every endpoint requires the caller's own Cognito access token and ACTIVE,
 * linked MintTick CUSTOMER account. Nothing is exposed from the legacy shared
 * operator SmartAPI session.
 */
@RestController
@RequestMapping("/api/customer/brokers/angel-one")
public class CustomerAngelOneController {
    private final CustomerIdentityStore identities;
    private final CustomerAngelOneSessionService broker;

    public CustomerAngelOneController(CustomerIdentityStore identities, CustomerAngelOneSessionService broker) {
        this.identities = identities;
        this.broker = broker;
    }

    public record ConnectRequest(
        @NotBlank @Size(max = 256) String apiKey,
        @NotBlank @Pattern(regexp = "(?i)[a-z0-9]{4,20}") String clientCode,
        @NotBlank @Pattern(regexp = "[0-9]{4,8}") String pin,
        @NotBlank @Pattern(regexp = "[0-9]{6}") String totp) {
        @Override public String toString() { return "ConnectRequest[redacted]"; }
    }

    @GetMapping("/status")
    public ResponseEntity<CustomerAngelOneSessionService.ConnectionStatus> status(
            org.springframework.security.core.Authentication authentication) {
        return noStore(broker.status(customer(authentication)));
    }

    @PostMapping("/connect")
    public ResponseEntity<CustomerAngelOneSessionService.ConnectionStatus> connect(
            @Valid @RequestBody ConnectRequest request,
            org.springframework.security.core.Authentication authentication) {
        UUID userId = customer(authentication);
        return noStore(broker.connect(userId, request.apiKey(), request.clientCode(), request.pin(), request.totp()));
    }

    @PostMapping("/disconnect")
    public ResponseEntity<CustomerAngelOneSessionService.ConnectionStatus> disconnect(
            org.springframework.security.core.Authentication authentication) {
        UUID userId = customer(authentication);
        broker.disconnect(userId);
        return noStore(broker.status(userId));
    }

    @GetMapping("/funds")
    public ResponseEntity<CustomerAngelOneSessionService.BrokerRead> funds(
            org.springframework.security.core.Authentication authentication) {
        return noStore(broker.funds(customer(authentication)));
    }

    @GetMapping("/holdings")
    public ResponseEntity<CustomerAngelOneSessionService.BrokerRead> holdings(
            org.springframework.security.core.Authentication authentication) {
        return noStore(broker.holdings(customer(authentication)));
    }

    @GetMapping("/positions")
    public ResponseEntity<CustomerAngelOneSessionService.BrokerRead> positions(
            org.springframework.security.core.Authentication authentication) {
        return noStore(broker.positions(customer(authentication)));
    }

    @GetMapping("/orders")
    public ResponseEntity<CustomerAngelOneSessionService.BrokerRead> orders(
            org.springframework.security.core.Authentication authentication) {
        return noStore(broker.orders(customer(authentication)));
    }

    // No place/modify/cancel-order route is intentionally exposed. Before live
    // execution, broker permission, registered IP, account-level capital,
    // appropriateness, order idempotency and reconciliation must be approved.
    private UUID customer(org.springframework.security.core.Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || !token.getAuthorities().stream().anyMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer authorization required");
        }
        return identities.find("cognito",
                token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a -> "ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer not linked"));
    }

    private <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    @ExceptionHandler(CustomerAngelOneSessionService.BrokerConnectionRequiredException.class)
    public ProblemDetail noConnection() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "Connect your own Angel One account to access broker data");
    }

    @ExceptionHandler(AngelOneException.class)
    public ProblemDetail providerFailure(AngelOneException ex) {
        HttpStatus status = switch (ex.kind()) {
            case NOT_CONFIGURED, SESSION_EXPIRED, UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case REJECTED, INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
        var problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty("code", ex.code());
        return problem;
    }
}
