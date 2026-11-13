package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Customer-owned room planning only. The mode records future PAPER or LIVE
 * intent, not consent to place orders or access a broker account.
 * All access is scoped to an ACTIVE application user resolved from the JWT.
 */
@RestController
@Validated
@RequestMapping("/api/customer/trading-rooms")
public class CustomerTradingRoomController {
    private final JdbcClient jdbc;
    private final CustomerIdentityStore identities;

    public CustomerTradingRoomController(JdbcClient jdbc, CustomerIdentityStore identities) {
        this.jdbc = jdbc;
        this.identities = identities;
    }

    public record RoomView(UUID id, String name, String focus, long plannedBudgetInr,
                           String riskPreference, String mode, String status, Instant createdAt) {}

    public record RenameRoomRequest(@NotBlank @Size(max = 80) String name) {}

    public record CreateRoomRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Pattern(regexp = "INTRADAY|SWING|LONG_TERM|HELP_ME_CHOOSE") String focus,
            @NotNull @Min(1000) @Max(10000000) Long plannedBudgetInr,
            @NotBlank @Pattern(regexp = "CAUTIOUS|BALANCED|AGGRESSIVE") String riskPreference,
            @NotBlank @Pattern(regexp = "PAPER|LIVE") String mode) {}

    @GetMapping
    public List<RoomView> list(org.springframework.security.core.Authentication authentication) {
        UUID owner = customer(authentication);
        return jdbc.sql("""
                SELECT id, name, focus, planned_budget_inr, risk_preference, mode, status, created_at
                FROM customer_trading_room WHERE user_id = :user
                ORDER BY created_at DESC, id DESC
                LIMIT 100
                """)
            .param("user", owner)
            .query((rs, i) -> map(rs))
            .list();
    }

    @GetMapping("/{roomId}")
    public RoomView get(@PathVariable UUID roomId,
                        org.springframework.security.core.Authentication authentication) {
        UUID owner = customer(authentication);
        return jdbc.sql("""
                SELECT id, name, focus, planned_budget_inr, risk_preference, mode, status, created_at
                FROM customer_trading_room WHERE id = :id AND user_id = :user
                """)
            .param("id", roomId).param("user", owner)
            .query((rs, i) -> map(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<RoomView> create(@Valid @RequestBody CreateRoomRequest request,
                                           org.springframework.security.core.Authentication authentication) {
        UUID owner = customer(authentication);
        String name = request.name().trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Room name is required");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO customer_trading_room
                        (id, user_id, name, focus, planned_budget_inr, risk_preference, mode, status)
                    VALUES (:id, :user, :name, :focus, :budget, :risk, :mode, 'DRAFT')
                    """)
                .param("id", id).param("user", owner).param("name", name)
                .param("focus", request.focus()).param("budget", request.plannedBudgetInr())
                .param("risk", request.riskPreference()).param("mode", request.mode())
                .update();
        } catch (DataIntegrityViolationException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A room with this name already exists or its settings are invalid");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(get(id, authentication));
    }

    /**
     * Only a user-owned display label may change, in any room state.
     * The room ID is stable: Paper wallets, fills, positions, broker
     * preferences and audit records remain associated with that ID.
     */
    @PutMapping("/{roomId}/name")
    @Transactional
    public RoomView rename(@PathVariable UUID roomId,
                           @Valid @RequestBody RenameRoomRequest request,
                           org.springframework.security.core.Authentication authentication) {
        UUID owner = customer(authentication);
        String normalized = request.name().trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty() || normalized.length() > 80 ||
                normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid room name");
        }
        try {
            int updated = jdbc.sql("""
                    UPDATE customer_trading_room
                       SET name = :name, updated_at = NOW()
                     WHERE id = :id AND user_id = :user
                    """)
                .param("name", normalized)
                .param("id", roomId)
                .param("user", owner)
                .update();
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
            }
        } catch (DataIntegrityViolationException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A room with this name already exists");
        }
        return get(roomId, authentication);
    }

    @DeleteMapping("/{roomId}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID roomId,
                                       org.springframework.security.core.Authentication authentication) {
        UUID owner = customer(authentication);
        int deleted = jdbc.sql("""
                DELETE FROM customer_trading_room
                WHERE id = :id AND user_id = :user AND status = 'DRAFT'
                """)
            .param("id", roomId).param("user", owner).update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft room not found");
        }
        return ResponseEntity.noContent().build();
    }

    private UUID customer(org.springframework.security.core.Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || !token.getAuthorities().stream().anyMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer session required");
        }
        return identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a -> "ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer not linked"));
    }

    private static RoomView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RoomView(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("focus"),
            rs.getLong("planned_budget_inr"),
            rs.getString("risk_preference"),
            rs.getString("mode"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant()
        );
    }
}
