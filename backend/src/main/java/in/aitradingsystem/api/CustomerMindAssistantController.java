package in.aitradingsystem.api;

import in.aitradingsystem.mind.MindAssistantService;
import in.aitradingsystem.user.CustomerIdentityStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only "Ask MintTick Mind" endpoint. The body is a question only; no trade parameters exist. */
@RestController
@RequestMapping("/api/customer/mind/v1/assistant")
public class CustomerMindAssistantController {
    private final MindAssistantService assistant;
    private final CustomerIdentityStore identities;

    public CustomerMindAssistantController(MindAssistantService assistant, CustomerIdentityStore identities) {
        this.assistant = assistant;
        this.identities = identities;
    }

    public record QuestionRequest(@NotNull @Size(min = 1, max = 280) String question) {}

    @PostMapping("/query")
    public ResponseEntity<MindAssistantService.Answer> query(@Valid @RequestBody QuestionRequest input,
                                                             Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(assistant.ask(customer(auth), input.question()));
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
}
