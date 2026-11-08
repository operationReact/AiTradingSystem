package in.aitradingsystem.provider.angelone;

import in.aitradingsystem.user.CustomerIdentityStore;
import in.aitradingsystem.config.AngelOneProperties;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private final AngelOneProperties configuredCredentials;
    private final OwnerAngelOneLambdaService lambdaBroker;
    private final OwnerAngelOneSsmService ownerSsm;
    private final CustomerAngelOneLiveQuoteService liveQuotes;
    private final String ownerCognitoSubject;

    // Retain the direct-credential test seam; production uses the injected
    // owner Lambda bridge instead of placing SecureStrings on the PAPER host.
    public CustomerAngelOneController(CustomerIdentityStore identities,
            CustomerAngelOneSessionService broker,
            AngelOneProperties configuredCredentials, String ownerCognitoSubject) {
        this(identities, broker, configuredCredentials, ownerCognitoSubject, null, null, null);
    }

    /** Backwards-compatible direct test seam; deployment injects the live service. */
    CustomerAngelOneController(CustomerIdentityStore identities,
            CustomerAngelOneSessionService broker,
            AngelOneProperties configuredCredentials, String ownerCognitoSubject,
            OwnerAngelOneLambdaService lambdaBroker) {
        this(identities, broker, configuredCredentials, ownerCognitoSubject, lambdaBroker, null, null);
    }

    /** Previous Lambda/live test seam remains source-compatible. */
    CustomerAngelOneController(CustomerIdentityStore identities,
            CustomerAngelOneSessionService broker,
            AngelOneProperties configuredCredentials, String ownerCognitoSubject,
            OwnerAngelOneLambdaService lambdaBroker,
            CustomerAngelOneLiveQuoteService liveQuotes) {
        this(identities, broker, configuredCredentials, ownerCognitoSubject,
                lambdaBroker, null, liveQuotes);
    }

    @Autowired
    public CustomerAngelOneController(CustomerIdentityStore identities,
            CustomerAngelOneSessionService broker,
            AngelOneProperties configuredCredentials,
            @Value("${MINTTICK_ANGEL_ONE_OWNER_SUB:}") String ownerCognitoSubject,
            OwnerAngelOneLambdaService lambdaBroker,
            OwnerAngelOneSsmService ownerSsm,
            CustomerAngelOneLiveQuoteService liveQuotes) {
        this.identities = identities;
        this.broker = broker;
        this.configuredCredentials = configuredCredentials;
        this.lambdaBroker = lambdaBroker;
        this.ownerSsm = ownerSsm;
        this.liveQuotes = liveQuotes;
        // Fail closed unless a single exact Cognito subject is explicitly allowed.
        this.ownerCognitoSubject = ownerCognitoSubject == null ? "" : ownerCognitoSubject.trim();
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
        UUID id = customer(authentication);
        var current = broker.status(id);
        if (current.connected()) return noStore(current);
        if (isAuthorizedOwner(authentication) && lambdaBroker != null
                && lambdaBroker.status(id).connected()) {
            return noStore(lambdaBroker.status(id));
        }
        return noStore(current);
    }

    @PostMapping("/connect")
    public ResponseEntity<CustomerAngelOneSessionService.ConnectionStatus> connect(
            @Valid @RequestBody ConnectRequest request,
            org.springframework.security.core.Authentication authentication) {
        UUID userId = customer(authentication);
        return noStore(broker.connect(userId, request.apiKey(), request.clientCode(), request.pin(), request.totp()));
    }

    /** Only the configured owner can use the existing server-side SSM credentials.
     *  These credentials are never returned to the device or shared with other customers.
     */
    public record ConfiguredLoginAvailability(boolean available) {}

    @GetMapping("/configured-login")
    public ResponseEntity<ConfiguredLoginAvailability> configuredLogin(
            org.springframework.security.core.Authentication authentication) {
        customer(authentication);
        return noStore(new ConfiguredLoginAvailability(
            isAuthorizedOwner(authentication) && (configuredCredentials.credentialsConfigured()
                || (ownerSsm != null && ownerSsm.available())
                || (lambdaBroker != null && lambdaBroker.available()))));
    }

    /** Self-only link reference. This is a Cognito subject, not an API key,
     * login PIN, broker owner grant, or the app_user UUID. A server operator
     * must separately authorize the subject in protected host settings.
     */
    public record OwnerLinkReference(String cognitoSubject) {}

    @GetMapping("/owner-link-reference")
    public ResponseEntity<OwnerLinkReference> ownerLinkReference(
            org.springframework.security.core.Authentication authentication) {
        customer(authentication);
        JwtAuthenticationToken token = (JwtAuthenticationToken) authentication;
        return noStore(new OwnerLinkReference(token.getToken().getSubject()));
    }

    @PostMapping("/connect-configured")
    public ResponseEntity<CustomerAngelOneSessionService.ConnectionStatus> connectConfigured(
            org.springframework.security.core.Authentication authentication) {
        UUID userId = customer(authentication);
        if (!isAuthorizedOwner(authentication)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner-only broker connection");
        }
        if (!configuredCredentials.credentialsConfigured()) {
            // Prefer the owner-only EC2 path after explicit EIP registration:
            // REST login and WebSocket now originate from the same static IP.
            if (ownerSsm != null && ownerSsm.available()) {
                if (liveQuotes != null) liveQuotes.disconnect(userId);
                broker.disconnect(userId);
                if (lambdaBroker != null) lambdaBroker.disconnect(userId);
                return noStore(ownerSsm.connect(userId));
            }
            if (lambdaBroker != null && lambdaBroker.available()) {
                return noStore(lambdaBroker.connect(userId));
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "The SSM-backed read-only Angel One connection is not configured");
        }
        final String totp;
        try {
            totp = AngelOneTotp.generate(configuredCredentials.totpSecret(), Instant.now());
        } catch (IllegalArgumentException ex) {
            // Do not include secret details in errors or logs.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "The server-side broker authentication is unavailable");
        }
        return noStore(broker.connect(userId, configuredCredentials.apiKey(),
            configuredCredentials.clientCode(), configuredCredentials.password(), totp));
    }

    private boolean isAuthorizedOwner(org.springframework.security.core.Authentication authentication) {
        return !ownerCognitoSubject.isBlank()
            && authentication instanceof JwtAuthenticationToken token
            && ownerCognitoSubject.equals(token.getToken().getSubject());
    }

    @PostMapping("/disconnect")
    public ResponseEntity<CustomerAngelOneSessionService.ConnectionStatus> disconnect(
            org.springframework.security.core.Authentication authentication) {
        UUID userId = customer(authentication);
        // Explicit customer revoke tears down the private stream immediately.
        if (liveQuotes != null) liveQuotes.disconnect(userId);
        if (lambdaBroker != null) lambdaBroker.disconnect(userId);
        broker.disconnect(userId);
        return noStore(broker.status(userId));
    }

    @GetMapping("/funds")
    public ResponseEntity<CustomerAngelOneSessionService.BrokerRead> funds(
            org.springframework.security.core.Authentication authentication) {
        UUID id = customer(authentication);
        if (broker.status(id).connected()) return noStore(broker.funds(id));
        if (isAuthorizedOwner(authentication) && lambdaBroker != null
                && lambdaBroker.status(id).connected()) {
            return noStore(lambdaBroker.read(id, "funds"));
        }
        return noStore(broker.funds(id));
    }

    @GetMapping("/holdings")
    public ResponseEntity<CustomerAngelOneSessionService.BrokerRead> holdings(
            org.springframework.security.core.Authentication authentication) {
        UUID id = customer(authentication);
        if (broker.status(id).connected()) return noStore(broker.holdings(id));
        if (isAuthorizedOwner(authentication) && lambdaBroker != null
                && lambdaBroker.status(id).connected()) {
            return noStore(lambdaBroker.read(id, "holdings"));
        }
        return noStore(broker.holdings(id));
    }

    @GetMapping("/positions")
    public ResponseEntity<CustomerAngelOneSessionService.BrokerRead> positions(
            org.springframework.security.core.Authentication authentication) {
        UUID id = customer(authentication);
        if (broker.status(id).connected()) return noStore(broker.positions(id));
        if (isAuthorizedOwner(authentication) && lambdaBroker != null
                && lambdaBroker.status(id).connected()) {
            return noStore(lambdaBroker.read(id, "positions"));
        }
        return noStore(broker.positions(id));
    }

    @GetMapping("/orders")
    public ResponseEntity<CustomerAngelOneSessionService.BrokerRead> orders(
            org.springframework.security.core.Authentication authentication) {
        UUID id = customer(authentication);
        if (broker.status(id).connected()) return noStore(broker.orders(id));
        if (isAuthorizedOwner(authentication) && lambdaBroker != null
                && lambdaBroker.status(id).connected()) {
            return noStore(lambdaBroker.read(id, "orders"));
        }
        return noStore(broker.orders(id));
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
