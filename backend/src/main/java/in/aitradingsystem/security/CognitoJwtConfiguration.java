package in.aitradingsystem.security;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/** Read-only JWT verification: never trusts an unverified claim or grants application roles from JWT groups. */
@Configuration
@ConditionalOnProperty(prefix = "app.security.cognito", name = "enabled", havingValue = "true")
public class CognitoJwtConfiguration {
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${app.security.cognito.issuer:}") String issuer,
            @Value("${app.security.cognito.client-id:}") String clientId,
            @Value("${app.security.cognito.region:ap-south-1}") String region) {
        // Pin the exact AWS issuer; never permit an arbitrary user-supplied JWKS.
        if (issuer.isBlank() || clientId.isBlank()
                || !issuer.matches("https://cognito-idp\\.[a-z0-9-]+\\.amazonaws\\.com/[a-z0-9-]+_[A-Za-z0-9]+")
                || !issuer.startsWith("https://cognito-idp." + region + ".amazonaws.com/")) {
            throw new IllegalStateException("Cognito issuer, client ID and region must be explicitly configured and match");
        }

        NimbusJwtDecoder decoder = NimbusJwtDecoder
            .withJwkSetUri(issuer + "/.well-known/jwks.json").build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            accessTokenValidator(clientId)));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> accessTokenValidator(String clientId) {
        return jwt -> {
            if (Objects.equals(jwt.getClaimAsString("token_use"), "access")
                    && Objects.equals(jwt.getClaimAsString("client_id"), clientId)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token", "Wrong Cognito token type or application client", null));
        };
    }

    @Bean
    CognitoIdentityProviderClient cognitoIdentityProviderClient(
            @Value("${app.security.cognito.region:ap-south-1}") String region) {
        return CognitoIdentityProviderClient.builder().region(Region.of(region)).build();
    }
}
