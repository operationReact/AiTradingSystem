package in.aitradingsystem.api;

import in.aitradingsystem.governance.ModelRegistryService;
import in.aitradingsystem.governance.ModelRegistryService.DefinitionSpec;
import in.aitradingsystem.governance.ModelRegistryService.ModelDefinitionView;
import in.aitradingsystem.governance.ModelRegistryService.ModelVersionView;
import in.aitradingsystem.governance.ModelRegistryService.VersionSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only V4 model identity registry. No update/delete endpoints exist by design. */
@RestController
@RequestMapping("/api/admin/model-governance/models")
@PreAuthorize("hasRole('ADMIN')")
public class ModelGovernanceRegistryController {
    private static final String HASH64 = "^[A-Fa-f0-9]{64}$";

    private final ModelRegistryService registry;

    public ModelGovernanceRegistryController(ModelRegistryService registry) {
        this.registry = registry;
    }

    public record CreateModelDefinitionRequest(
        @NotBlank @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$") String modelKey,
        @NotBlank @Size(max = 120) String displayName,
        @NotBlank @Size(max = 40)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_]{1,39}$") String family,
        @NotBlank @Pattern(regexp = "(?i)^(ALPHA|TEMPORAL|REGIME|NLP|DECISION|ANOMALY|RL)$") String role,
        @NotBlank @Size(max = 120) String provider,
        @NotBlank @Pattern(regexp = "(?i)^(UNKNOWN|REVIEW_REQUIRED|APPROVED_COMMERCIAL|RESEARCH_ONLY|REJECTED)$")
        String licenseStatus,
        @Size(max = 512) String description) {}

    public record CreateModelVersionRequest(
        @NotBlank @Size(max = 128) String versionLabel,
        @NotBlank @Pattern(regexp = HASH64) String artifactFingerprint,
        @NotBlank @Pattern(regexp = HASH64) String featureSchemaFingerprint,
        @NotBlank @Pattern(regexp = HASH64) String trainingDatasetFingerprint,
        @NotBlank @Pattern(regexp = HASH64) String trainingConfigFingerprint,
        @NotBlank @Pattern(regexp = "^[A-Fa-f0-9]{40,64}$") String codeSha,
        @Pattern(regexp = HASH64) String researchRegistryFingerprint,
        Instant trainingCutoff) {}

    @GetMapping
    public List<ModelDefinitionView> definitions() {
        return registry.definitions();
    }

    @PostMapping
    public ResponseEntity<ModelDefinitionView> createDefinition(
            @Valid @RequestBody CreateModelDefinitionRequest request) {
        ModelDefinitionView created = registry.createDefinition(new DefinitionSpec(
            request.modelKey(), request.displayName(), request.family(), request.role(),
            request.provider(), request.licenseStatus(), request.description()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{modelKey}")
    public ModelDefinitionView definition(@PathVariable String modelKey) {
        return registry.definitionByKey(modelKey);
    }

    @GetMapping("/{modelKey}/versions")
    public List<ModelVersionView> versions(@PathVariable String modelKey) {
        return registry.versions(modelKey);
    }

    @PostMapping("/{modelKey}/versions")
    public ResponseEntity<ModelVersionView> createVersion(
            @PathVariable String modelKey,
            @Valid @RequestBody CreateModelVersionRequest request) {
        ModelVersionView created = registry.createVersion(modelKey, new VersionSpec(
            request.versionLabel(), request.artifactFingerprint(), request.featureSchemaFingerprint(),
            request.trainingDatasetFingerprint(), request.trainingConfigFingerprint(), request.codeSha(),
            request.researchRegistryFingerprint(), request.trainingCutoff()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        detail.setTitle("Model registry entry not found");
        return detail;
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail conflict(DuplicateKeyException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, "Model definition/version identity already exists and immutable registry entries cannot be replaced.");
        detail.setTitle("Immutable model registry conflict");
        return detail;
    }
}
