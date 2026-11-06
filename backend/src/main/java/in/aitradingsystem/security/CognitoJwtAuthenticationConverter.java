package in.aitradingsystem.security;

import in.aitradingsystem.user.CustomerIdentityStore;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Only ACTIVE, linked MintTick users receive server-side database roles.
 * A new Cognito identity has no app roles until /api/auth/cognito/provision.
 */
@Component
public class CognitoJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final CustomerIdentityStore identities;

    public CognitoJwtAuthenticationConverter(CustomerIdentityStore identities) {
        this.identities = identities;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        var account = identities.find("cognito", jwt.getIssuer().toString(), jwt.getSubject());
        var roles = account.filter(a -> "ACTIVE".equals(a.status()))
            .map(a -> List.of(new SimpleGrantedAuthority("ROLE_" + a.role())))
            .orElseGet(List::of);
        // The token subject is never used as our user ID or source of permissions.
        return new JwtAuthenticationToken(jwt, roles, jwt.getSubject());
    }
}
