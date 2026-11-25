package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only room dashboard, scoped by the authenticated customer on every join.
 * PAPER numbers are virtual; a LIVE room deliberately has no assumed cash,
 * fills, or broker P&L. This endpoint never authorizes or submits an order.
 */
@RestController
@RequestMapping("/api/customer/trading-rooms/dashboard")
public class CustomerRoomDashboardController {
    private final JdbcClient jdbc;
    private final CustomerIdentityStore identities;

    public CustomerRoomDashboardController(JdbcClient jdbc, CustomerIdentityStore identities) {
        this.jdbc = jdbc;
        this.identities = identities;
    }

    public record RoomMetric(
        UUID roomId, String mode, String status, long plannedBudgetInr,
        Long availableCashPaise, Boolean paperHalted,
        long positionCount, long paperFillCount, long blockedIntentCount
    ) {}

    @GetMapping
    public ResponseEntity<List<RoomMetric>> dashboard(Authentication authentication) {
        UUID owner = customer(authentication);
        List<RoomMetric> rows = jdbc.sql("""
            SELECT r.id, r.mode, r.status, r.planned_budget_inr,
                a.available_cash_paise, a.halted,
                (SELECT COUNT(*) FROM customer_paper_manual_position p
                    WHERE p.user_id = :user AND p.room_id = r.id
                      AND r.mode = 'PAPER' AND p.quantity > 0) AS position_count,
                (SELECT COUNT(*) FROM customer_paper_manual_order o
                    WHERE o.user_id = :user AND o.room_id = r.id
                      AND r.mode = 'PAPER') AS paper_fill_count,
                (SELECT COUNT(*) FROM customer_trade_intent i
                    WHERE i.user_id = :user AND i.room_id = r.id) AS intent_count
            FROM customer_trading_room r
            LEFT JOIN customer_paper_account a
                ON r.mode = 'PAPER' AND a.room_id = r.id AND a.user_id = :user
            WHERE r.user_id = :user
            ORDER BY r.created_at DESC, r.id DESC
            LIMIT 100
            """)
            .param("user", owner)
            .query((rs, index) -> new RoomMetric(
                rs.getObject("id", UUID.class),
                rs.getString("mode"), rs.getString("status"),
                rs.getLong("planned_budget_inr"),
                (Long) rs.getObject("available_cash_paise"),
                (Boolean) rs.getObject("halted"),
                rs.getLong("position_count"), rs.getLong("paper_fill_count"),
                rs.getLong("intent_count")
            )).list();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows);
    }

    private UUID customer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream().noneMatch(
                    a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer session required");
        }
        return identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a -> "ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Customer not linked"));
    }
}
