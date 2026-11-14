package in.aitradingsystem.provider.upstox;

import java.time.Clock;
import java.time.Instant;
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
 * Owner-only observations of database checkpoint state, never credentials or
 * trading authority. Separate from proven ingestion acceptance: there is no
 * invented targetUnits or progressProof in this endpoint.
 */
@RestController
@RequestMapping("/api/customer/owner-upstox-analytics/v1")
public class UpstoxPersistedReceiptsController {
    private final UpstoxPersistedIngestionReceipts store;
    private final Clock clock;
    private final String ownerSubject;

    public UpstoxPersistedReceiptsController(
            UpstoxPersistedIngestionReceipts store, Clock clock,
            @Value("${MINTTICK_ANGEL_ONE_OWNER_SUB:}") String ownerSubject) {
        this.store = store;
        this.clock = clock;
        this.ownerSubject = ownerSubject;
    }

    public record Response(
            String contractVersion,
            Instant generatedAt,
            String provider,
            boolean modelTrainingRightsVerified,
            boolean paperTradingEnabled,
            UpstoxPersistedIngestionReceipts.Snapshot receipts) {}

    @GetMapping("/persisted-receipts")
    public ResponseEntity<Response> read(Authentication authentication) {
        if (ownerSubject.isBlank()
                || !(authentication instanceof JwtAuthenticationToken jwt)
                || !ownerSubject.equals(jwt.getToken().getSubject())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner only");
        }
        Instant now = clock.instant();
        var snapshot = store.snapshot(now);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Response("minttick.upstox.persisted-receipts.v1",
                        now, "UPSTOX", false, false, snapshot));
    }
}
