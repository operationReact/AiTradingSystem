package in.aitradingsystem.api;

import in.aitradingsystem.mind.MindCapabilityProgressService;
import in.aitradingsystem.user.CustomerIdentityStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only Mintick Mind progress registry plus owner notes / recheck requests.
 * There is intentionally no endpoint that sets a capability status.
 */
@RestController
@Validated
@RequestMapping("/api/customer/mind/v1/progress")
public class CustomerMindProgressController {
    private final MindCapabilityProgressService progress;
    private final CustomerIdentityStore identities;

    public CustomerMindProgressController(MindCapabilityProgressService progress,
                                          CustomerIdentityStore identities) {
        this.progress = progress;
        this.identities = identities;
    }

    public record NoteRequest(
        @NotNull @Pattern(regexp = "NOTE|RECHECK_REQUESTED") String type,
        @Size(max = 500) String text) {}

    @GetMapping
    public ResponseEntity<MindCapabilityProgressService.Progress> inspect(Authentication auth) {
        return noStore(progress.inspect(customer(auth)));
    }

    @GetMapping("/items/{capabilityId}")
    public ResponseEntity<MindCapabilityProgressService.Detail> detail(
            @PathVariable @Pattern(regexp = "[a-z]+\\.[A-Za-z0-9._-]{1,90}") String capabilityId,
            Authentication auth) {
        return noStore(progress.detail(customer(auth), capabilityId));
    }

    @PostMapping("/items/{capabilityId}/notes")
    public ResponseEntity<MindCapabilityProgressService.Event> note(
            @PathVariable @Pattern(regexp = "[a-z]+\\.[A-Za-z0-9._-]{1,90}") String capabilityId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody NoteRequest input, Authentication auth) {
        return noStore(progress.addNote(customer(auth), capabilityId,
            MindCapabilityProgressService.EventType.valueOf(input.type()), input.text(), key));
    }

    private UUID customer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream()
                    .noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer session required");
        }
        return identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(account -> "ACTIVE".equals(account.status())
                && "CUSTOMER".equals(account.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Customer not linked"));
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
