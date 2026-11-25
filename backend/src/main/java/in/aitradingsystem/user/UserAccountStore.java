package in.aitradingsystem.user;

import static in.aitradingsystem.user.UserPreferenceModels.AppUser;
import static in.aitradingsystem.user.UserPreferenceModels.NotificationPreference;
import static in.aitradingsystem.user.UserPreferenceModels.UserPreference;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side Phase 10 account/preference persistence. Authentication credentials are deliberately
 * not stored here; external_subject is an opaque identity-provider subject and audit metadata never
 * contains tokens or secrets.
 */
@Repository
public class UserAccountStore {
    private final JdbcClient jdbc;

    public UserAccountStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public AppUser createUser(String externalSubject, String displayName, String email, String actor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, external_subject, display_name, email, created_by, updated_by)
                VALUES (:id, :externalSubject, :displayName, :email, :actor, :actor)
                """)
            .param("id", id)
            .param("externalSubject", requireBounded(externalSubject, 200, "externalSubject"))
            .param("displayName", nullableBounded(displayName, 120, "displayName"))
            .param("email", nullableBounded(email, 320, "email"))
            .param("actor", requireBounded(actor, 120, "actor"))
            .update();

        jdbc.sql("""
                INSERT INTO user_preference (user_id, created_by, updated_by)
                VALUES (:userId, :actor, :actor)
                """)
            .param("userId", id)
            .param("actor", actor)
            .update();
        jdbc.sql("""
                INSERT INTO user_notification_preference (user_id, created_by, updated_by)
                VALUES (:userId, :actor, :actor)
                """)
            .param("userId", id)
            .param("actor", actor)
            .update();
        return findUser(id).orElseThrow();
    }

    public Optional<AppUser> findUser(UUID id) {
        return jdbc.sql("""
                SELECT id, external_subject, display_name, email, status,
                       created_at, created_by, updated_at, updated_by
                FROM app_user WHERE id = :id
                """)
            .param("id", id)
            .query(UserAccountStore::mapUser)
            .optional();
    }

    public Optional<AppUser> findUserByExternalSubject(String externalSubject) {
        return jdbc.sql("""
                SELECT id, external_subject, display_name, email, status,
                       created_at, created_by, updated_at, updated_by
                FROM app_user WHERE external_subject = :externalSubject
                """)
            .param("externalSubject", externalSubject)
            .query(UserAccountStore::mapUser)
            .optional();
    }

    public Optional<UserPreference> findPreferences(UUID userId) {
        return jdbc.sql("""
                SELECT user_id, version, experience_mode, theme_mode, density_mode, spacing_scale,
                       motion_enabled, haptics_enabled, prompt_mobile_downloads, default_route,
                       default_interval, default_candle_count, diagnostics_opt_in,
                       stale_refresh_attempts, stale_threshold_seconds, updated_at, updated_by
                FROM user_preference WHERE user_id = :userId
                """)
            .param("userId", userId)
            .query(UserAccountStore::mapPreferences)
            .optional();
    }

    public Optional<NotificationPreference> findNotificationPreferences(UUID userId) {
        return jdbc.sql("""
                SELECT user_id, version, in_app_enabled, email_enabled, push_enabled,
                       weekly_digest_enabled, security_alerts_enabled,
                       aggregate_operator_alerts_by_root_cause, operator_coverage_alert_threshold,
                       updated_at, updated_by
                FROM user_notification_preference WHERE user_id = :userId
                """)
            .param("userId", userId)
            .query(UserAccountStore::mapNotificationPreferences)
            .optional();
    }

    /** Compare-and-set update. Empty means the expected version was stale or the user does not exist. */
    public Optional<UserPreference> updatePreferences(UserPreference p, long expectedVersion, String actor) {
        int updated = jdbc.sql("""
                UPDATE user_preference
                SET experience_mode = :experienceMode,
                    theme_mode = :themeMode,
                    density_mode = :densityMode,
                    spacing_scale = :spacingScale,
                    motion_enabled = :motionEnabled,
                    haptics_enabled = :hapticsEnabled,
                    prompt_mobile_downloads = :promptMobileDownloads,
                    default_route = :defaultRoute,
                    default_interval = :defaultInterval,
                    default_candle_count = :defaultCandleCount,
                    diagnostics_opt_in = :diagnosticsOptIn,
                    stale_refresh_attempts = :staleRefreshAttempts,
                    stale_threshold_seconds = :staleThresholdSeconds,
                    version = version + 1,
                    updated_at = now(),
                    updated_by = :actor
                WHERE user_id = :userId AND version = :expectedVersion
                """)
            .param("userId", p.userId())
            .param("expectedVersion", expectedVersion)
            .param("experienceMode", p.experienceMode())
            .param("themeMode", p.themeMode())
            .param("densityMode", p.densityMode())
            .param("spacingScale", p.spacingScale())
            .param("motionEnabled", p.motionEnabled())
            .param("hapticsEnabled", p.hapticsEnabled())
            .param("promptMobileDownloads", p.promptMobileDownloads())
            .param("defaultRoute", p.defaultRoute())
            .param("defaultInterval", p.defaultInterval())
            .param("defaultCandleCount", p.defaultCandleCount())
            .param("diagnosticsOptIn", p.diagnosticsOptIn())
            .param("staleRefreshAttempts", p.staleRefreshAttempts())
            .param("staleThresholdSeconds", p.staleThresholdSeconds())
            .param("actor", requireBounded(actor, 120, "actor"))
            .update();
        return updated == 1 ? findPreferences(p.userId()) : Optional.empty();
    }

    /** Security alerts are intentionally forced on server-side and cannot be disabled by a client. */
    public Optional<NotificationPreference> updateNotificationPreferences(
            NotificationPreference p, long expectedVersion, String actor) {
        int updated = jdbc.sql("""
                UPDATE user_notification_preference
                SET in_app_enabled = :inAppEnabled,
                    email_enabled = :emailEnabled,
                    push_enabled = :pushEnabled,
                    weekly_digest_enabled = :weeklyDigestEnabled,
                    security_alerts_enabled = TRUE,
                    aggregate_operator_alerts_by_root_cause = :aggregateByRootCause,
                    operator_coverage_alert_threshold = :coverageThreshold,
                    version = version + 1,
                    updated_at = now(),
                    updated_by = :actor
                WHERE user_id = :userId AND version = :expectedVersion
                """)
            .param("userId", p.userId())
            .param("expectedVersion", expectedVersion)
            .param("inAppEnabled", p.inAppEnabled())
            .param("emailEnabled", p.emailEnabled())
            .param("pushEnabled", p.pushEnabled())
            .param("weeklyDigestEnabled", p.weeklyDigestEnabled())
            .param("aggregateByRootCause", p.aggregateOperatorAlertsByRootCause())
            .param("coverageThreshold", p.operatorCoverageAlertThreshold())
            .param("actor", requireBounded(actor, 120, "actor"))
            .update();
        return updated == 1 ? findNotificationPreferences(p.userId()) : Optional.empty();
    }

    private static AppUser mapUser(ResultSet rs, int row) throws SQLException {
        return new AppUser(
            rs.getObject("id", UUID.class), rs.getString("external_subject"), rs.getString("display_name"),
            rs.getString("email"), rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("created_by"), rs.getObject("updated_at", OffsetDateTime.class), rs.getString("updated_by"));
    }

    private static UserPreference mapPreferences(ResultSet rs, int row) throws SQLException {
        return new UserPreference(
            rs.getObject("user_id", UUID.class), rs.getLong("version"), rs.getString("experience_mode"),
            rs.getString("theme_mode"), rs.getString("density_mode"), rs.getBigDecimal("spacing_scale"),
            rs.getBoolean("motion_enabled"), rs.getBoolean("haptics_enabled"),
            rs.getBoolean("prompt_mobile_downloads"), rs.getString("default_route"),
            rs.getString("default_interval"), rs.getInt("default_candle_count"),
            rs.getBoolean("diagnostics_opt_in"), rs.getInt("stale_refresh_attempts"),
            rs.getInt("stale_threshold_seconds"), rs.getObject("updated_at", OffsetDateTime.class),
            rs.getString("updated_by"));
    }

    private static NotificationPreference mapNotificationPreferences(ResultSet rs, int row) throws SQLException {
        return new NotificationPreference(
            rs.getObject("user_id", UUID.class), rs.getLong("version"), rs.getBoolean("in_app_enabled"),
            rs.getBoolean("email_enabled"), rs.getBoolean("push_enabled"), rs.getBoolean("weekly_digest_enabled"),
            rs.getBoolean("security_alerts_enabled"), rs.getBoolean("aggregate_operator_alerts_by_root_cause"),
            rs.getBigDecimal("operator_coverage_alert_threshold"), rs.getObject("updated_at", OffsetDateTime.class),
            rs.getString("updated_by"));
    }

    private static String requireBounded(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must be non-empty and at most " + max + " characters");
        }
        return value;
    }

    private static String nullableBounded(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
        return value == null || value.isBlank() ? null : value;
    }
}
