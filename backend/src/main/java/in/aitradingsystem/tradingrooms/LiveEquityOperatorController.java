package in.aitradingsystem.tradingrooms;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-only LIVE intraday dispatch controls. Reachable only with ADMIN or
 * OPERATOR (see SecurityConfig). It is not on the public customer account edge,
 * so customers cannot reach it. Responses contain only the operator's own
 * action ids, never customer or broker data.
 */
@RestController
@Validated
@RequestMapping("/api/operator/live-equity")
public class LiveEquityOperatorController {
    private final LiveEquityOperatorService operator;

    public LiveEquityOperatorController(LiveEquityOperatorService operator) {
        this.operator = operator;
    }

    public record AttestHostingRequest(
            @NotNull @Pattern(regexp = "ANGEL_ONE|UPSTOX") String broker,
            @NotBlank @Size(max = 45) String egressIp,
            @NotBlank @Size(max = 200) String operatorReference,
            @NotNull @Min(1) @Max(90 * 24 * 3600) Long validForSeconds) {}

    public record RevokeHostingRequest(@NotBlank @Size(max = 200) String operatorReference) {}

    public record RiskPolicyRequest(
            @NotNull @Min(1) @Max(10_000_000L) Long maxAccountIntradayBuyInr,
            @NotNull @Min(1) @Max(10_000_000L) Long maxOrderValueInr,
            @NotNull @Min(1) @Max(10_000) Integer maxOrderQuantity,
            @NotNull @Min(0) @Max(10_000_000L) Long dailyRealizedLossLimitInr,
            @NotBlank @Size(max = 200) String operatorReference) {}

    public record ResolveNotPlacedRequest(
            @NotBlank @Size(max = 200) String operatorReference,
            @NotBlank @Size(max = 200) String evidenceReference) {}

    public record ActionResult(String status, UUID id) {}

    @PostMapping("/hosting-attestations")
    public ResponseEntity<LiveEquityOperatorService.Attestation> attestHosting(
            @Valid @RequestBody AttestHostingRequest input) {
        var result = operator.attestHosting(input.broker(), input.egressIp(),
            input.operatorReference(), Duration.ofSeconds(input.validForSeconds()));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(result);
    }

    @DeleteMapping("/hosting-attestations/{id}")
    public ResponseEntity<ActionResult> revokeHosting(@PathVariable UUID id,
            @Valid @RequestBody RevokeHostingRequest input) {
        operator.revokeHosting(id, input.operatorReference());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ActionResult("REVOKED", id));
    }

    @PutMapping("/customers/{customerId}/risk-policy")
    public ResponseEntity<ActionResult> setRiskPolicy(@PathVariable UUID customerId,
            @Valid @RequestBody RiskPolicyRequest input) {
        operator.setRiskPolicy(customerId, new LiveEquityOperatorService.RiskPolicyInput(
            input.maxAccountIntradayBuyInr(), input.maxOrderValueInr(),
            input.maxOrderQuantity(), input.dailyRealizedLossLimitInr()),
            input.operatorReference());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ActionResult("SAVED", customerId));
    }

    @PostMapping("/orders/{orderId}/resolve-not-placed")
    public ResponseEntity<ActionResult> resolveNotPlaced(@PathVariable UUID orderId,
            @Valid @RequestBody ResolveNotPlacedRequest input) {
        operator.resolveNotPlaced(orderId, input.operatorReference(), input.evidenceReference());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ActionResult("RESOLVED_NOT_PLACED", orderId));
    }
}
