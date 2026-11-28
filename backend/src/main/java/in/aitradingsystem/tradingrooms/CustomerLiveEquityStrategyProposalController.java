package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner-only handoff from an eligible Mind PAPER strategy cycle to the existing
 * guarded LIVE intraday equity order path. Creating a proposal never places an
 * order; submission still needs explicit per-order consent and all server gates.
 */
@RestController
@Validated
@RequestMapping("/api/customer/trading-rooms/{roomId}/live/equity/strategy-proposals")
public class CustomerLiveEquityStrategyProposalController {
    private final LiveEquityStrategyProposalService proposals;
    private final CustomerIdentityStore identities;

    public CustomerLiveEquityStrategyProposalController(
            LiveEquityStrategyProposalService proposals, CustomerIdentityStore identities) {
        this.proposals = proposals;
        this.identities = identities;
    }

    public record CreateRequest(@NotNull UUID sourcePaperRoomId, @NotNull UUID sourceCycleId) {}

    @PostMapping
    public ResponseEntity<LiveEquityStrategyProposalService.Proposal> create(
            @PathVariable UUID roomId, @Valid @RequestBody CreateRequest request,
            Authentication authentication) {
        var proposal = proposals.create(customer(authentication), roomId,
            request.sourcePaperRoomId(), request.sourceCycleId());
        return noStore(proposal);
    }

    @PostMapping("/{proposalId}/submit")
    public ResponseEntity<LiveEquityStrategyProposalService.Submission> submit(
            @PathVariable UUID roomId, @PathVariable UUID proposalId,
            @RequestHeader(value="X-Confirm-Live-Equity-Order", required=false) String confirmation,
            Authentication authentication) {
        var submission = proposals.submit(customer(authentication), roomId, proposalId, confirmation);
        return ResponseEntity.status(submission.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .cacheControl(CacheControl.noStore()).body(submission);
    }

    private UUID customer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream()
                    .noneMatch(authority -> "ROLE_CUSTOMER".equals(authority.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer session required");
        }
        return identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(account -> "ACTIVE".equals(account.status()) && "CUSTOMER".equals(account.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Customer not linked"));
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
