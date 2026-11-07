package in.aitradingsystem.provider.upstox;

import in.aitradingsystem.provider.upstox.UpstoxApiClient.UpstoxProviderException;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Customer's Upstox link: login occurs on Upstox, not in MintTick.
 *
 * <p>Only the OAuth callback is public. Its state is 256-bit, customer-bound,
 * short-lived and consumed exactly once before exchanging the one-time code.
 * No token, code, broker account ID or secret is emitted by the callback.
 */
@RestController
@RequestMapping("/api/customer/brokers/upstox")
public class CustomerUpstoxController {
    private final CustomerIdentityStore identities;
    private final CustomerUpstoxOAuthService broker;

    public CustomerUpstoxController(CustomerIdentityStore identities,
                                    CustomerUpstoxOAuthService broker) {
        this.identities = identities;
        this.broker = broker;
    }

    @PostMapping("/start")
    public ResponseEntity<CustomerUpstoxOAuthService.StartResponse> start(Authentication authentication) {
        return secure(broker.start(customer(authentication)));
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error) {
        boolean successful = false;
        try {
            if (error == null || error.isBlank()) {
                broker.complete(state, code);
                successful = true;
            } else {
                // Consume a valid pending state even when Upstox declines login.
                broker.complete(state, null);
            }
        } catch (UpstoxProviderException rejected) {
            // No raw broker error, OAuth code or state is ever rendered.
        }
        String page = successful
            ? "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Upstox connected</title></head><body>"
                + "<h2>Upstox connected to MintTick</h2>"
                + "<p>Return to MintTick and tap Refresh to verify your account.</p>"
                + "<p>Live order execution is not enabled by connecting.</p></body></html>"
            : "<!doctype html><html><head><title>Upstox authorization failed</title></head><body>"
                + "<h2>Could not connect Upstox</h2>"
                + "<p>Return to MintTick and start a fresh connection attempt.</p></body></html>";
        return ResponseEntity.status(successful ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
            .contentType(MediaType.TEXT_HTML)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.REFERRER_POLICY, "no-referrer")
            .body(page);
    }

    @GetMapping("/status")
    public ResponseEntity<CustomerUpstoxOAuthService.Status> status(Authentication authentication) {
        return secure(broker.status(customer(authentication)));
    }

    @PostMapping("/disconnect")
    public ResponseEntity<CustomerUpstoxOAuthService.Status> disconnect(Authentication authentication) {
        UUID customer = customer(authentication);
        broker.disconnect(customer);
        return secure(broker.status(customer));
    }

    @GetMapping("/funds")
    public ResponseEntity<CustomerUpstoxOAuthService.AccountData> funds(Authentication authentication) {
        return secure(broker.funds(customer(authentication)));
    }

    @GetMapping("/holdings")
    public ResponseEntity<CustomerUpstoxOAuthService.AccountData> holdings(Authentication authentication) {
        return secure(broker.holdings(customer(authentication)));
    }

    @GetMapping("/positions")
    public ResponseEntity<CustomerUpstoxOAuthService.AccountData> positions(Authentication authentication) {
        return secure(broker.positions(customer(authentication)));
    }

    @GetMapping("/orders")
    public ResponseEntity<CustomerUpstoxOAuthService.AccountData> orders(Authentication authentication) {
        return secure(broker.orders(customer(authentication)));
    }

    @GetMapping("/registered-ips")
    public ResponseEntity<CustomerUpstoxOAuthService.AccountData> registeredIps(Authentication authentication) {
        return secure(broker.staticIps(customer(authentication)));
    }

    @ExceptionHandler(UpstoxProviderException.class)
    public ProblemDetail failClosed(UpstoxProviderException exception) {
        HttpStatus status = switch (exception.code()) {
            case "UPSTOX_APP_NOT_CONFIGURED", "UPSTOX_AUTH_UNAVAILABLE", "UPSTOX_READ_UNAVAILABLE",
                 "UPSTOX_AUTH_CAPACITY_REACHED" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "UPSTOX_NOT_CONNECTED", "UPSTOX_ACCOUNT_ALREADY_CONNECTED" -> HttpStatus.CONFLICT;
            case "UPSTOX_OAUTH_STATE_INVALID", "UPSTOX_AUTH_FAILED",
                 "UPSTOX_PROFILE_REJECTED" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_GATEWAY;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            status, "Upstox connection could not be verified");
        problem.setProperty("code", exception.code());
        return problem;
    }

    private UUID customer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream()
                    .noneMatch(authority -> "ROLE_CUSTOMER".equals(authority.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer session required");
        }
        return identities.find("cognito",
                token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(account -> "ACTIVE".equals(account.status()) && "CUSTOMER".equals(account.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Customer account not linked"));
    }

    private static <T> ResponseEntity<T> secure(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
