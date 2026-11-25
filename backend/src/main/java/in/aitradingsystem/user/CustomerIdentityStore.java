package in.aitradingsystem.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider-neutral mapping to app_user UUID; never links accounts by email
 * alone. No password, bearer token, or authentication secret is persisted.
 */
@Repository
public class CustomerIdentityStore {
    private final JdbcClient jdbc;
    private final UserAccountStore users;

    public CustomerIdentityStore(JdbcClient jdbc, UserAccountStore users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public Optional<CustomerAccount> find(String provider, String issuer, String subject) {
        return jdbc.sql("""
                SELECT u.id, u.status, u.role, u.display_name, u.email
                FROM app_auth_identity i
                JOIN app_user u ON u.id = i.user_id
                WHERE i.provider = :provider AND i.issuer = :issuer AND i.subject = :subject
                """)
            .param("provider", provider)
            .param("issuer", issuer)
            .param("subject", subject)
            .query((rs, row) -> new CustomerAccount(
                rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getString("role"), rs.getString("display_name"), rs.getString("email")))
            .optional();
    }

    @Transactional
    public CustomerAccount provisionVerifiedIdentity(
            String provider, String issuer, String subject, String username, String verifiedEmail) {
        if (!"cognito".equals(provider) || issuer == null || issuer.length() > 255
                || subject == null || subject.isBlank() || subject.length() > 200
                || verifiedEmail == null || verifiedEmail.isBlank()) {
            throw new IllegalArgumentException("A verified supported identity is required");
        }

        Optional<CustomerAccount> existing = find(provider, issuer, subject);
        if (existing.isPresent()) {
            if (!"ACTIVE".equals(existing.get().status())) {
                throw new AccountDisabledException();
            }
            return existing.get();
        }

        // The legacy required external_subject is a stable keyed namespace,
        // not the application's permanent primary identifier.
        String externalSubject = provider + ":" + issuer.substring(issuer.lastIndexOf('/') + 1) + ":" + subject;
        if (externalSubject.length() > 200) {
            throw new IllegalArgumentException("Identity subject too long");
        }
        try {
            var newUser = users.createUser(
                externalSubject, username == null ? "MintTick user" : username,
                verifiedEmail, "system:cognito-verified");
            // Ordinary public registrations must never inherit the legacy VIEWER
            // API permissions (which can include internal operational data).
            jdbc.sql("UPDATE app_user SET role = 'CUSTOMER' WHERE id = :userId")
                .param("userId", newUser.id()).update();
            jdbc.sql("""
                    INSERT INTO app_auth_identity
                        (provider, issuer, subject, user_id)
                    VALUES (:provider, :issuer, :subject, :userId)
                    """)
                .param("provider", provider)
                .param("issuer", issuer)
                .param("subject", subject)
                .param("userId", newUser.id())
                .update();
        } catch (DataIntegrityViolationException conflict) {
            // Never merge another local user simply because the email matches.
            throw new IdentityConflictException();
        }
        return find(provider, issuer, subject).orElseThrow();
    }

    public record CustomerAccount(UUID id, String status, String role, String displayName, String email) {}

    public static final class AccountDisabledException extends RuntimeException {}
    public static final class IdentityConflictException extends RuntimeException {}
}
