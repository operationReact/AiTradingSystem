package in.aitradingsystem.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class CognitoJwtConfigurationTest {

    private static Jwt token(String use, String clientId) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("redacted.test.jwt")
            .header("alg", "RS256")
            .issuer("https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_test")
            .subject("customer-sub")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(900))
            .claim("token_use", use)
            .claim("client_id", clientId)
            .build();
    }

    @Test
    void acceptsOnlyAccessTokensFromThePinnedPublicClient() {
        var validator = CognitoJwtConfiguration.accessTokenValidator("minttick-cognito-client");
        assertFalse(validator.validate(token("access", "minttick-cognito-client")).hasErrors());
        assertTrue(validator.validate(token("id", "minttick-cognito-client")).hasErrors());
        assertTrue(validator.validate(token("access", "unrelated-app-client")).hasErrors());
    }
}
