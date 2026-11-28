package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Customer-owned room notes. These are private planning text, never trading authority. */
@RestController
@Validated
@RequestMapping("/api/customer/trading-rooms/{roomId}/thesis")
public class CustomerTradingRoomThesisController {
    private static final int MAX_CONTENT_LENGTH = 5000;

    private final JdbcClient jdbc;
    private final CustomerIdentityStore identities;

    public CustomerTradingRoomThesisController(JdbcClient jdbc,
                                                CustomerIdentityStore identities) {
        this.jdbc = jdbc;
        this.identities = identities;
    }

    public record ThesisView(UUID roomId, String content, long revision, Instant updatedAt) {}

    public record SaveThesisRequest(
            @NotNull @Size(max = MAX_CONTENT_LENGTH) String content,
            @Min(0) long expectedRevision) {}

    @GetMapping
    public ThesisView get(@PathVariable UUID roomId, Authentication authentication) {
        UUID owner = customer(authentication);
        return jdbc.sql("""
                SELECT r.id AS room_id, t.content, t.revision, t.updated_at
                  FROM customer_trading_room r
                  LEFT JOIN customer_trading_room_thesis t
                    ON t.room_id = r.id AND t.user_id = r.user_id
                 WHERE r.id = :room AND r.user_id = :owner
                """)
            .param("room", roomId)
            .param("owner", owner)
            .query((rs, row) -> new ThesisView(
                rs.getObject("room_id", UUID.class),
                rs.getString("content") == null ? "" : rs.getString("content"),
                rs.getObject("revision") == null ? 0L : rs.getLong("revision"),
                rs.getTimestamp("updated_at") == null
                    ? null : rs.getTimestamp("updated_at").toInstant()))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Room not found"));
    }

    @PutMapping
    @Transactional
    public ThesisView save(@PathVariable UUID roomId,
                           @Valid @RequestBody SaveThesisRequest request,
                           Authentication authentication) {
        UUID owner = customer(authentication);
        String content = normalize(request.content());
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Room notes cannot exceed 5000 characters");
        }
        if (content.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\t')) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Room notes contain unsupported control characters");
        }

        // Serialize writes for this room, then compare the client's revision.
        // A stale editor receives 409 and cannot silently overwrite newer text.
        String mode = jdbc.sql("""
                SELECT mode FROM customer_trading_room
                 WHERE id = :room AND user_id = :owner FOR UPDATE
                """)
            .param("room", roomId)
            .param("owner", owner)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Room not found"));

        long currentRevision = jdbc.sql("""
                SELECT revision FROM customer_trading_room_thesis
                 WHERE room_id = :room AND user_id = :owner
                """)
            .param("room", roomId)
            .param("owner", owner)
            .query(Long.class)
            .optional()
            .orElse(0L);
        if (currentRevision != request.expectedRevision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Room notes changed elsewhere. Reload before saving.");
        }

        if (currentRevision == 0) {
            jdbc.sql("""
                    INSERT INTO customer_trading_room_thesis
                        (room_id, user_id, mode, content, revision, updated_at)
                    VALUES (:room, :owner, :mode, :content, 1, NOW())
                    """)
                .param("room", roomId)
                .param("owner", owner)
                .param("mode", mode)
                .param("content", content)
                .update();
        } else {
            jdbc.sql("""
                    UPDATE customer_trading_room_thesis
                       SET content = :content, revision = revision + 1, updated_at = NOW()
                     WHERE room_id = :room AND user_id = :owner AND revision = :revision
                    """)
                .param("content", content)
                .param("room", roomId)
                .param("owner", owner)
                .param("revision", currentRevision)
                .update();
        }
        return get(roomId, authentication);
    }

    private UUID customer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream().noneMatch(a ->
                    "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Customer session required");
        }
        return identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(account -> "ACTIVE".equals(account.status())
                && "CUSTOMER".equals(account.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Customer not linked"));
    }

    private static String normalize(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }
}
