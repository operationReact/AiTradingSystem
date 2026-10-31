package in.aitradingsystem.user;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class UserPreferenceModels {
    private UserPreferenceModels() {}

    public record AppUser(
        UUID id,
        String externalSubject,
        String displayName,
        String email,
        String status,
        OffsetDateTime createdAt,
        String createdBy,
        OffsetDateTime updatedAt,
        String updatedBy
    ) {}

    public record UserPreference(
        UUID userId,
        long version,
        String experienceMode,
        String themeMode,
        String densityMode,
        BigDecimal spacingScale,
        boolean motionEnabled,
        boolean hapticsEnabled,
        boolean promptMobileDownloads,
        String defaultRoute,
        String defaultInterval,
        int defaultCandleCount,
        boolean diagnosticsOptIn,
        int staleRefreshAttempts,
        int staleThresholdSeconds,
        OffsetDateTime updatedAt,
        String updatedBy
    ) {}

    public record NotificationPreference(
        UUID userId,
        long version,
        boolean inAppEnabled,
        boolean emailEnabled,
        boolean pushEnabled,
        boolean weeklyDigestEnabled,
        boolean securityAlertsEnabled,
        boolean aggregateOperatorAlertsByRootCause,
        BigDecimal operatorCoverageAlertThreshold,
        OffsetDateTime updatedAt,
        String updatedBy
    ) {}
}
