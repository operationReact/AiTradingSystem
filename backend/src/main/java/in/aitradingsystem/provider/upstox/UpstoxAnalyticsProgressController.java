package in.aitradingsystem.provider.upstox;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cognito owner-only read of real, persisted scheduler status.
 * The existing customer's broker OAuth connection is NOT an Analytics-token
 * health claim. This endpoint cannot start jobs, trade, or reveal credentials.
 */
@RestController
@RequestMapping("/api/customer/owner-upstox-analytics/v1")
public class UpstoxAnalyticsProgressController {
    private final UpstoxAnalyticsProgressRepository progress;
    private final String ownerSubject;
    private final Clock clock;

    public UpstoxAnalyticsProgressController(UpstoxAnalyticsProgressRepository progress,
            @Value("${MINTTICK_ANGEL_ONE_OWNER_SUB:}") String ownerSubject,
            Clock clock) {
        this.progress = progress;
        this.ownerSubject = ownerSubject;
        this.clock = clock;
    }

    public record Overview(String contractVersion, Instant generatedAt, String source,
                           boolean liveTradingEnabled, boolean paperTradingEnabled,
                           boolean modelTrainingRightsVerified,
                           List<UpstoxAnalyticsProgressRepository.Task> tasks) {}

    @GetMapping("/progress")
    public ResponseEntity<Overview> progress(Authentication authentication) {
        if (ownerSubject.isBlank()
                || !(authentication instanceof JwtAuthenticationToken jwt)
                || !ownerSubject.equals(jwt.getToken().getSubject())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner only");
        }
        Instant now = clock.instant();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Overview("minttick.upstox.analytics.progress.v1",
                        now, "UPSTOX_ANALYTICS_READONLY",
                        false, false, false, progress.snapshot(now)));
    }
}
