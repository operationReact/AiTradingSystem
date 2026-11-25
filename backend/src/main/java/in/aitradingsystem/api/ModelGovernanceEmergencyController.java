package in.aitradingsystem.api;

import in.aitradingsystem.governance.ModelEmergencyAuditHistoryService;
import in.aitradingsystem.governance.ModelEmergencyControlService;
import in.aitradingsystem.governance.ModelEmergencyControlService.ControlView;
import in.aitradingsystem.governance.ModelEmergencyControlService.EffectiveModelControlView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only V4 emergency model controls. These endpoints can remove model influence only;
 * they cannot enable LIVE trading, bypass deterministic risk or grant capital authority.
 */
@RestController
@RequestMapping("/api/admin/model-governance/emergency-controls")
@PreAuthorize("hasRole('ADMIN')")
public class ModelGovernanceEmergencyController {
    private final ModelEmergencyControlService controls;
    private final ModelEmergencyAuditHistoryService history;

    public ModelGovernanceEmergencyController(
            ModelEmergencyControlService controls, ModelEmergencyAuditHistoryService history) {
        this.controls = controls;
        this.history = history;
    }

    public record DisabledRequest(boolean disabled, @NotBlank @Size(max = 512) String reason) {}
    public record StaticFallbackRequest(boolean enabled, @NotBlank @Size(max = 512) String reason) {}

    @GetMapping
    public List<ControlView> controls() {
        return controls.controls();
    }

    /** Bounded, read-only timeline of Java-committed de-risk control changes. */
    @GetMapping("/history")
    public List<ModelEmergencyAuditHistoryService.HistoryEvent> history(
            @RequestParam(required = false) String controlType,
            @RequestParam(required = false) String scopeKey,
            @RequestParam(defaultValue = "25") int limit) {
        return history.recent(controlType, scopeKey, limit);
    }

    @GetMapping("/models/{versionId}/effective")
    public EffectiveModelControlView effectiveModel(@PathVariable UUID versionId) {
        return controls.effectiveModelControl(versionId);
    }

    @PutMapping("/models/{versionId}")
    public ControlView setModelDisabled(
            @PathVariable UUID versionId,
            @Valid @RequestBody DisabledRequest request) {
        return controls.setModelVersionDisabled(versionId, request.disabled(), request.reason());
    }

    @PutMapping("/families/{family}")
    public ControlView setFamilyDisabled(
            @PathVariable String family,
            @Valid @RequestBody DisabledRequest request) {
        return controls.setModelFamilyDisabled(family, request.disabled(), request.reason());
    }

    @PutMapping("/static-fallback")
    public ControlView setStaticFallback(
            @Valid @RequestBody StaticFallbackRequest request) {
        return controls.setStaticFallbackEnabled(request.enabled(), request.reason());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        detail.setTitle("Model governance target not found");
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setTitle("Invalid model governance control");
        return detail;
    }
}
