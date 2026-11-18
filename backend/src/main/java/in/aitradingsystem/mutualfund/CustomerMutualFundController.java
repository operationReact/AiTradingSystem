package in.aitradingsystem.mutualfund;

import in.aitradingsystem.user.CustomerIdentityStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
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
 * Authenticated self-directed SIP DRAFTS. These routes cannot register a
 * mandate, activate a SIP, collect payment or submit a mutual-fund purchase.
 * Live activation must be a separate, provider-verified flow after onboarding.
 */
@RestController
@Validated
@RequestMapping("/api/customer/mutual-funds")
public final class CustomerMutualFundController {
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private final JdbcClient jdbc;
    private final CustomerIdentityStore identities;

    public CustomerMutualFundController(JdbcClient jdbc, CustomerIdentityStore identities) {
        this.jdbc = jdbc;
        this.identities = identities;
    }

    public record Capability(boolean livePurchaseEnabled, boolean automaticDebitEnabled,
                             boolean readOnlyUpstoxAvailable, String recommendedIntegration,
                             String blocker) {}
    public record CreatePlan(
        @NotBlank @Pattern(regexp = "INF[A-Z0-9]{9}") String isin,
        @NotBlank @Size(min = 3, max = 160) String schemeName,
        @NotNull @Min(10000) @Max(10000000) Long amountPaise,
        @NotNull @Min(10000) @Max(10000000) Long maxMonthlyPaise,
        @NotNull @Min(1) @Max(28) Integer sipDay) {}
    public record Plan(UUID id, String isin, String schemeName,
                       long amountPaise, long maxMonthlyPaise, int sipDay,
                       LocalDate firstDueOn, String status, Instant createdAt) {}

    @GetMapping("/capability")
    public ResponseEntity<Capability> capability(Authentication authentication) {
        owner(authentication);
        return noStore(new Capability(false, false, true, "FINTECH_PRIMITIVES_OR_NSE_MF_INVEST",
            "Live SIP and payment mandate partner onboarding and signed callbacks not configured"));
    }

    @GetMapping("/plans")
    public ResponseEntity<List<Plan>> plans(Authentication authentication) {
        UUID user = owner(authentication);
        return noStore(jdbc.sql("""
                SELECT id, isin, scheme_name, amount_paise, max_monthly_paise,
                       sip_day, first_due_on, status, created_at
                FROM customer_mf_plan WHERE user_id = :user
                ORDER BY created_at DESC, id DESC LIMIT 100
                """)
            .param("user", user).query((rs, row) -> map(rs)).list());
    }

    @GetMapping("/plans/{id}")
    public ResponseEntity<Plan> plan(@PathVariable UUID id, Authentication authentication) {
        return noStore(get(owner(authentication), id));
    }

    @PostMapping("/plans")
    @Transactional
    public ResponseEntity<Plan> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePlan request,
            Authentication authentication) {
        UUID user = owner(authentication);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid idempotency key");
        }
        try {
            MutualFundScheduleRules.checkPlan(request.isin(), request.schemeName(),
                request.amountPaise(), request.maxMonthlyPaise(), request.sipDay());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
        String scheme = request.schemeName().trim();
        LocalDate firstDue = MutualFundScheduleRules.firstDue(
            LocalDate.now(INDIA), request.sipDay());
        // Atomic idempotency; a different payload with the same key is rejected
        // instead of silently returning a plan with incompatible instructions.
        int created = jdbc.sql("""
                INSERT INTO customer_mf_plan
                    (id, user_id, isin, scheme_name, amount_paise,
                     max_monthly_paise, sip_day, first_due_on, idempotency_key, status)
                VALUES (:id, :user, :isin, :scheme, :amount,
                    :cap, :day, :first, :key, 'DRAFT')
                ON CONFLICT (user_id, idempotency_key) DO NOTHING
                """)
            .param("id", UUID.randomUUID()).param("user", user)
            .param("isin", request.isin()).param("scheme", scheme)
            .param("amount", request.amountPaise()).param("cap", request.maxMonthlyPaise())
            .param("day", request.sipDay()).param("first", firstDue)
            .param("key", idempotencyKey).update();
        Plan result = jdbc.sql("""
                SELECT id, isin, scheme_name, amount_paise, max_monthly_paise,
                       sip_day, first_due_on, status, created_at
                FROM customer_mf_plan WHERE user_id = :user AND idempotency_key = :key
                """)
            .param("user", user).param("key", idempotencyKey)
            .query((rs, row) -> map(rs)).single();
        if (!result.isin().equals(request.isin()) || !result.schemeName().equals(scheme)
                || result.amountPaise() != request.amountPaise()
                || result.maxMonthlyPaise() != request.maxMonthlyPaise()
                || result.sipDay() != request.sipDay()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Idempotency key already belongs to a different investment plan");
        }
        return ResponseEntity.status(created == 1 ? HttpStatus.CREATED : HttpStatus.OK)
            .cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/plans/{id}/pause-draft")
    @Transactional
    public ResponseEntity<Plan> pauseDraft(@PathVariable UUID id, Authentication authentication) {
        UUID user = owner(authentication);
        int updated = jdbc.sql("""
                UPDATE customer_mf_plan SET status = 'PAUSED', updated_at = NOW()
                WHERE id = :id AND user_id = :user AND status = 'DRAFT'
                """).param("id", id).param("user", user).update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only drafts can be paused here; live SIP pause needs provider confirmation");
        }
        return noStore(get(user, id));
    }

    @PostMapping("/plans/{id}/resume-draft")
    @Transactional
    public ResponseEntity<Plan> resumeDraft(@PathVariable UUID id, Authentication authentication) {
        UUID user = owner(authentication);
        int updated = jdbc.sql("""
                UPDATE customer_mf_plan SET status = 'DRAFT', updated_at = NOW()
                WHERE id = :id AND user_id = :user AND status = 'PAUSED'
                    AND mandate_id IS NULL
                """).param("id", id).param("user", user).update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Live SIP resume requires provider authorization");
        }
        return noStore(get(user, id));
    }

    private Plan get(UUID user, UUID id) {
        return jdbc.sql("""
                SELECT id, isin, scheme_name, amount_paise, max_monthly_paise,
                       sip_day, first_due_on, status, created_at
                FROM customer_mf_plan WHERE id = :id AND user_id = :user
                """).param("id", id).param("user", user)
            .query((rs, row) -> map(rs))
            .optional().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Mutual-fund plan not found"));
    }

    private UUID owner(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream().noneMatch(
                    a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer session required");
        }
        return identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a -> "ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer account not linked"));
    }

    private static Plan map(ResultSet rs) throws SQLException {
        return new Plan(rs.getObject("id", UUID.class), rs.getString("isin"),
            rs.getString("scheme_name"), rs.getLong("amount_paise"),
            rs.getLong("max_monthly_paise"), rs.getInt("sip_day"),
            rs.getDate("first_due_on").toLocalDate(), rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
