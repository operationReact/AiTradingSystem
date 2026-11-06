package in.aitradingsystem.security;

import in.aitradingsystem.user.CustomerIdentityStore;
import in.aitradingsystem.user.CustomerIdentityStore.AccountDisabledException;
import in.aitradingsystem.user.CustomerIdentityStore.IdentityConflictException;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;

/**
 * Customer identity provisioning: a valid ACCESS JWT alone is not sufficient
 * to create an app account. Cognito must independently confirm the email.
 */
@RestController
@RequestMapping("/api/auth/cognito")
@ConditionalOnProperty(prefix = "app.security.cognito", name = "enabled", havingValue = "true")
public class CognitoAccountController {
    private final CustomerIdentityStore identities;
    private final CognitoIdentityProviderClient provider;

    public CognitoAccountController(CustomerIdentityStore identities, CognitoIdentityProviderClient provider) {
        this.identities = identities;
        this.provider = provider;
    }

    @PostMapping("/provision")
    public AccountView provision(org.springframework.security.core.Authentication authentication) {
        JwtAuthenticationToken token = requireCognito(authentication);
        String issuer = token.getToken().getIssuer().toString();
        String subject = token.getToken().getSubject();
        var existing = identities.find("cognito", issuer, subject);
        if (existing.isPresent()) {
            if (!"ACTIVE".equals(existing.get().status())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account unavailable");
            }
            return view(existing.get());
        }

        GetUserResponse user;
        try {
            // GetUser requires a valid Cognito access token. It does not
            // require an IAM user credential or expose the user's password.
            user = provider.getUser(GetUserRequest.builder()
                .accessToken(token.getToken().getTokenValue()).build());
        } catch (CognitoIdentityProviderException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identity verification failed");
        }
        Map<String, String> attributes = user.userAttributes().stream()
            .collect(java.util.stream.Collectors.toMap(a -> a.name(), a -> a.value(), (a, b) -> a));
        if (!"true".equalsIgnoreCase(attributes.get("email_verified"))
                || attributes.getOrDefault("email", "").isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Verified email required");
        }

        // Rely on Cognito-signed access JWT for subject and issuer; GetUser
        // verifies the token again and supplies verified email attributes.
        try {
            return view(identities.provisionVerifiedIdentity("cognito", issuer, subject,
                user.username(), attributes.get("email")));
        } catch (AccountDisabledException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account unavailable");
        } catch (IdentityConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account recovery required");
        }
    }

    @GetMapping("/me")
    public AccountView me(org.springframework.security.core.Authentication authentication) {
        JwtAuthenticationToken token = requireCognito(authentication);
        return identities.find("cognito",
                token.getToken().getIssuer().toString(), token.getToken().getSubject())
            .filter(a -> "ACTIVE".equals(a.status()))
            .map(CognitoAccountController::view)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private static JwtAuthenticationToken requireCognito(org.springframework.security.core.Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return token;
    }

    private static AccountView view(CustomerIdentityStore.CustomerAccount account) {
        return new AccountView(account.id(), account.displayName(), account.email(), account.role());
    }

    public record AccountView(UUID userId, String username, String email, String role) {}
}
