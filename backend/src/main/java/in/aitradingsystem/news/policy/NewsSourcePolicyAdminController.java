package in.aitradingsystem.news.policy;

import in.aitradingsystem.news.NewsSource;
import in.aitradingsystem.news.policy.NewsSourcePolicy.LicenseState;
import in.aitradingsystem.news.policy.NewsSourcePolicy.Row;
import in.aitradingsystem.news.policy.NewsSourcePolicy.Use;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only write surface for the pre-seeded, default-deny news publisher policies. */
@RestController
@RequestMapping("/api/admin/market-intelligence/sources")
@PreAuthorize("hasRole('ADMIN')")
public class NewsSourcePolicyAdminController {
    private final NewsSourcePolicyService policies;

    public NewsSourcePolicyAdminController(NewsSourcePolicyService policies) {
        this.policies = policies;
    }

    public record PolicyRequest(
        @NotNull LicenseState licenseState,
        @NotNull @Size(max = 4) List<@NotNull Use> permittedUses,
        @NotBlank @Size(max = 200) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/#@ -]{0,199}") String reviewerRef,
        @Size(max = 500) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/#@ -]{0,499}") String termsRef,
        Instant effectiveFrom,
        Instant effectiveTo,
        @Min(1) @Max(365) int contentTtlDays) {}

    public record PolicyResponse(String sourceId, String publisher, String licenseState,
                                 List<String> permittedUses, String reviewerRef, String termsRef,
                                 Instant effectiveFrom, Instant effectiveTo, int contentTtlDays) {}

    @PutMapping("/{sourceId}/policy")
    public ResponseEntity<PolicyResponse> update(
            @PathVariable String sourceId, @Valid @RequestBody PolicyRequest request) {
        NewsSource source = NewsSource.SUPPORTED.stream()
            .filter(candidate -> candidate.id().equals(sourceId))
            .findFirst().orElseThrow(() -> new NoSuchElementException("Supported news source not found"));

        if (request.licenseState() == LicenseState.APPROVED) {
            if (request.permittedUses().isEmpty() || request.effectiveFrom() == null
                    || request.termsRef() == null || request.termsRef().isBlank()) {
                throw new IllegalArgumentException(
                    "Approval requires at least one permitted use, terms reference, and effective start");
            }
        } else if (!request.permittedUses().isEmpty()) {
            throw new IllegalArgumentException("Non-approved policies must have no permitted uses");
        }
        if (request.effectiveFrom() != null && request.effectiveTo() != null
                && !request.effectiveTo().isAfter(request.effectiveFrom())) {
            throw new IllegalArgumentException("Policy end must be after its effective start");
        }

        Row saved = policies.update(new Row(source.id(), source.publisher(), request.licenseState(),
            request.permittedUses(), request.reviewerRef(), request.termsRef(),
            Optional.ofNullable(request.effectiveFrom()), Optional.ofNullable(request.effectiveTo()),
            request.contentTtlDays()));
        PolicyResponse response = new PolicyResponse(saved.sourceId(), saved.publisher(), saved.state().name(),
            saved.permittedUses().stream().map(Enum::name).sorted().toList(), saved.reviewerRef(),
            saved.termsRef(), saved.effectiveFrom().orElse(null), saved.effectiveTo().orElse(null),
            saved.contentTtlDays());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        detail.setTitle("Supported news source not found");
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setTitle("Invalid news source policy");
        return detail;
    }
}
