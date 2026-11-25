package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner-only, database-only F&O PAPER history. This remains available when
 * external options rights, market feed and order execution are disabled.
 *
 * There is intentionally no POST route, upstream provider, quote read or
 * registration under any trading feature flag. An absent position is not
 * transformed into a market valuation; its unrealized P&L remains unknown.
 */
@RestController
@RequestMapping("/api/customer/trading-rooms/{roomId}/paper/fno")
public class CustomerPaperFnoHistoryController {
    private final JdbcClient jdbc;
    private final CustomerIdentityStore identities;

    public CustomerPaperFnoHistoryController(JdbcClient jdbc, CustomerIdentityStore identities) {
        this.jdbc = jdbc;
        this.identities = identities;
    }

    @GetMapping("/orders")
    @Transactional(readOnly = true)
    public ResponseEntity<List<CustomerPaperFnoOrderService.Fill>> orders(
            @PathVariable UUID roomId, Authentication authentication) {
        UUID owner = owner(authentication);
        requireRoom(owner, roomId);
        var data = jdbc.sql("""
            SELECT id,room_id,instrument_key,option_type,expiry,side,quantity,
                   lot_size,limit_price,fill_price,notional_paise,estimated_fees_paise,
                   realized_pnl_delta_paise,cash_after_paise,exchange_at,observed_at,created_at
            FROM customer_paper_fno_order
            WHERE room_id=:room AND user_id=:owner
            ORDER BY created_at DESC,id DESC LIMIT 100
            """).param("room", roomId).param("owner", owner)
            .query((rs, n) -> new CustomerPaperFnoOrderService.Fill(
                rs.getObject("id", UUID.class), rs.getObject("room_id", UUID.class),
                rs.getString("instrument_key"), rs.getString("option_type"),
                rs.getObject("expiry", LocalDate.class), rs.getString("side"),
                rs.getInt("quantity"), rs.getInt("lot_size"),
                rs.getBigDecimal("limit_price"), rs.getBigDecimal("fill_price"),
                rs.getLong("notional_paise"), rs.getLong("estimated_fees_paise"),
                rs.getLong("realized_pnl_delta_paise"), rs.getLong("cash_after_paise"),
                rs.getTimestamp("exchange_at").toInstant(),
                rs.getTimestamp("observed_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()))
            .list();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);
    }

    @GetMapping("/positions")
    @Transactional(readOnly = true)
    public ResponseEntity<List<CustomerPaperFnoOrderService.Position>> positions(
            @PathVariable UUID roomId, Authentication authentication) {
        UUID owner = owner(authentication);
        requireRoom(owner, roomId);
        var data = jdbc.sql("""
            SELECT instrument_key,option_type,expiry,lot_size,quantity,cost_basis_paise
            FROM customer_paper_fno_position
            WHERE room_id=:room AND user_id=:owner AND quantity>0
            ORDER BY expiry,instrument_key LIMIT 100
            """).param("room", roomId).param("owner", owner)
            .query((rs, n) -> new CustomerPaperFnoOrderService.Position(
                rs.getString("instrument_key"), rs.getString("option_type"),
                rs.getObject("expiry", LocalDate.class), rs.getInt("lot_size"),
                rs.getInt("quantity"), rs.getLong("cost_basis_paise"),
                null, false))
            .list();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);
    }

    private void requireRoom(UUID owner, UUID roomId) {
        boolean allowed = jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM customer_trading_room
                WHERE id=:room AND user_id=:owner AND mode='PAPER'
                  AND status='PAPER_FUNDED' AND market_scope IN ('FNO','ALL'))
            """).param("room", roomId).param("owner", owner)
            .query(Boolean.class).single();
        if (!allowed) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Funded PAPER F&O room not found");
    }

    private UUID owner(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream()
                    .noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Customer session required");
        }
        return identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a -> "ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Customer not linked"));
    }
}
