package in.aitradingsystem.tradingrooms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.OffsetDateTime;
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
 * Owner-only read of host-exported SAFE METAdata; never reads archive candles,
 * broker credentials, sqlite, or arbitrary paths. This is not market data.
 */
@RestController
@RequestMapping("/api/customer/owner-history/v1")
public final class CustomerHistoricalProgressController {
    private static final int MAX_BYTES = 80_000;
    private static final String SCHEMA = "minttick.owner.history.progress.v1";
    private final String ownerSubject;
    private final Path snapshot;
    private final ObjectMapper json;

    public CustomerHistoricalProgressController(
            @Value("${MINTTICK_ANGEL_ONE_OWNER_SUB:}") String ownerSubject,
            @Value("${MINTTICK_OWNER_HISTORY_PROGRESS_PATH:/run/minttick-owner-history/progress.json}")
                String snapshotPath,
            ObjectMapper json) {
        this.ownerSubject = ownerSubject;
        this.snapshot = Path.of(snapshotPath);
        this.json = json;
    }

    @GetMapping("/progress")
    public ResponseEntity<JsonNode> progress(Authentication authentication) {
        if (ownerSubject.isBlank()
                || !(authentication instanceof JwtAuthenticationToken jwt)
                || !ownerSubject.equals(jwt.getToken().getSubject())) {
            // Cognito owner identity is mandatory even for a ROLE_CUSTOMER.
            // No legacy operator API key, demo account or arbitrary customer.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner only");
        }
        try {
            if (Files.isSymbolicLink(snapshot)
                    || !Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(snapshot) > MAX_BYTES) {
                throw new IOException("Snapshot absent or oversized");
            }
            byte[] bytes = Files.readAllBytes(snapshot);
            if (bytes.length > MAX_BYTES) {
                throw new IOException("Snapshot too large");
            }
            JsonNode data = json.readTree(bytes);
            if (data == null || !SCHEMA.equals(data.path("schema").asText())
                    || !data.path("task").isObject()
                    || !data.path("scheduler").isObject()
                    || !data.path("task").path("years").isArray()
                    || data.path("task").path("years").size() != 21
                    || !data.path("recentRuns").isArray()
                    || data.path("recentRuns").size() > 8
                    || !data.path("recentRequests").isArray()
                    || data.path("recentRequests").size() > 12
                    || !"ANGEL_ONE".equals(data.path("source").asText())
                    || !"ONE_DAY".equals(data.path("task").path("interval").asText())
                    || !data.path("task").path("candles").canConvertToLong()
                    || data.path("task").path("candles").asLong() < 0
                    || !data.path("task").path("yearCoverageVerified").isBoolean()
                    || data.path("task").path("yearCoverageVerified").asBoolean()
                    || !data.path("task").path("listingHistoryVerified").isBoolean()
                    || data.path("task").path("listingHistoryVerified").asBoolean()) {
                throw new IOException("Unverified progress metadata");
            }
            // Never label a stale host report as live scheduler status.
            OffsetDateTime captured = OffsetDateTime.parse(data.path("generatedAt").asText());
            if (captured.isAfter(OffsetDateTime.now().plusMinutes(2))
                    || captured.isBefore(OffsetDateTime.now().minusHours(48))) {
                throw new IOException("Progress snapshot stale");
            }
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(data);
        } catch (IOException | RuntimeException error) {
            if (error instanceof ResponseStatusException response) {
                throw response;
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Historical download monitor is not connected");
        }
    }
}
