package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.mind.MindReadinessService;
import in.aitradingsystem.provider.angelone.CustomerAngelOneController;
import in.aitradingsystem.provider.upstox.CustomerUpstoxController;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.sql.SQLException;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Customer-scoped, read-only homepage summary. No AI fixtures, inferred fills,
 * broker account balances, personal secrets, orders or execution permissions.
 * Component reads fail closed independently: one unavailable broker or research
 * feed must not erase verified customer room records.
 */
@RestController
@RequestMapping("/api/customer/home/v1")
public final class CustomerHomeSummaryController {
    private final JdbcClient jdbc;
    private final CustomerIdentityStore identities;
    private final CustomerAngelOneController angel;
    private final CustomerUpstoxController upstox;
    private final MindReadinessService mind;

    public CustomerHomeSummaryController(
            JdbcClient jdbc, CustomerIdentityStore identities,
            CustomerAngelOneController angel, CustomerUpstoxController upstox,
            MindReadinessService mind) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.angel = angel;
        this.upstox = upstox;
        this.mind = mind;
    }

    public record RoomPreview(UUID id, String name, String focus, String mode,
                              String status, long plannedBudgetInr,
                              Instant createdAt) {}
    public record Rooms(int count, int drafts, int funded, long plannedBudgetInr,
                        Long availablePaperCashPaise, List<RoomPreview> featured) {}
    public record BrokerConnections(String angelOne, String upstox,
                                    int connected, boolean complete) {}
    public record Evidence(String status, Instant asOf, String source) {}
    public record MindState(Evidence market, Evidence research,
                            boolean executionEnabled) {}
    public record LatestActivity(String kind, String description,
                                 Instant occurredAt, UUID roomId) {}
    public record NextAction(String code, UUID roomId, String destination) {}
    public record Summary(String contractVersion, String mode, Instant generatedAt,
                          Rooms rooms, BrokerConnections brokers, MindState mind,
                          LatestActivity activity, NextAction nextAction,
                          boolean liveExecutionEnabled) {}

    private record RoomCounts(int count, int draftCount, int fundedCount,
                              long plannedBudgetInr) {}
    private record WalletTotals(int walletCount, long availableCashPaise) {}

    @GetMapping("/summary")
    public ResponseEntity<Summary> summary(
            @RequestParam(defaultValue = "PAPER") String mode,
            Authentication authentication) {
        if (!"PAPER".equals(mode) && !"LIVE".equals(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Workspace mode must be PAPER or LIVE");
        }
        UUID owner = customer(authentication);

        RoomCounts counts = jdbc.sql("""
            SELECT COUNT(*) AS room_count,
                   COUNT(*) FILTER (WHERE status = 'DRAFT') AS draft_count,
                   COUNT(*) FILTER (WHERE status = 'PAPER_FUNDED') AS funded_count,
                   COALESCE(SUM(planned_budget_inr), 0) AS planned_budget
            FROM customer_trading_room
            WHERE user_id = :owner AND mode = :mode
            """).param("owner", owner).param("mode", mode)
            .query((rs, row) -> new RoomCounts(
                Math.toIntExact(rs.getLong("room_count")),
                Math.toIntExact(rs.getLong("draft_count")),
                Math.toIntExact(rs.getLong("funded_count")),
                rs.getLong("planned_budget"))).single();

        List<RoomPreview> featured = jdbc.sql("""
            SELECT id, name, focus, mode, status, planned_budget_inr, created_at
            FROM customer_trading_room
            WHERE user_id = :owner AND mode = :mode
            ORDER BY CASE WHEN status = 'DRAFT' THEN 0 ELSE 1 END,
                     created_at DESC, id DESC
            LIMIT 2
            """).param("owner", owner).param("mode", mode)
            .query((rs, row) -> mapRoom(rs)).list();

        Long virtualCash = null;
        if ("PAPER".equals(mode)) {
            // A funded room without its wallet is an integrity failure, not
            // proof of a zero balance. No virtual amount crosses into LIVE.
            WalletTotals totals = jdbc.sql("""
                SELECT COUNT(a.room_id) AS wallet_count,
                       COALESCE(SUM(a.available_cash_paise), 0) AS cash_paise
                FROM customer_trading_room r
                LEFT JOIN customer_paper_account a
                  ON a.room_id = r.id AND a.user_id = r.user_id
                WHERE r.user_id = :owner AND r.mode = 'PAPER'
                  AND r.status = 'PAPER_FUNDED'
                """).param("owner", owner).query((rs, row) ->
                    new WalletTotals(Math.toIntExact(rs.getLong("wallet_count")),
                        rs.getLong("cash_paise"))).single();
            if (totals.walletCount() == counts.fundedCount()) {
                virtualCash = totals.availableCashPaise();
            }
        }

        Rooms rooms = new Rooms(counts.count(), counts.draftCount(),
            counts.fundedCount(), counts.plannedBudgetInr(), virtualCash, featured);
        BrokerConnections brokers = new BrokerConnections(
            angelStatus(authentication), upstoxStatus(authentication), 0, false);
        int connected = ("CONNECTED".equals(brokers.angelOne()) ? 1 : 0)
            + ("CONNECTED".equals(brokers.upstox()) ? 1 : 0);
        brokers = new BrokerConnections(brokers.angelOne(), brokers.upstox(),
            connected, !"UNAVAILABLE".equals(brokers.angelOne())
                && !"UNAVAILABLE".equals(brokers.upstox()));

        MindState mindState;
        try {
            MindReadinessService.Overview verified = mind.inspect();
            mindState = new MindState(
                evidence(verified.market()), evidence(verified.research()),
                false);
        } catch (RuntimeException unavailable) {
            mindState = new MindState(
                new Evidence("UNAVAILABLE", null, null),
                new Evidence("UNAVAILABLE", null, null), false);
        }

        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Summary("customer.home.v1", mode, Instant.now(),
                rooms, brokers, mindState, latestActivity(owner, mode),
                nextAction(mode, rooms, brokers), false));
    }

    private LatestActivity latestActivity(UUID owner, String mode) {
        // A persisted room plan is activity, not evidence of order execution.
        List<LatestActivity> latestRoom = jdbc.sql("""
            SELECT id, created_at FROM customer_trading_room
            WHERE user_id = :owner AND mode = :mode
            ORDER BY created_at DESC, id DESC LIMIT 1
            """).param("owner", owner).param("mode", mode)
            .query((rs, row) -> new LatestActivity(
                "ROOM_PLAN_SAVED", "Trading Room plan saved",
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("id", UUID.class))).list();
        LatestActivity selected = latestRoom.isEmpty() ? null : latestRoom.get(0);
        if ("PAPER".equals(mode)) {
            List<LatestActivity> cashEvents = jdbc.sql("""
                SELECT e.room_id, e.event_kind, e.created_at
                FROM customer_paper_cash_event e
                WHERE e.user_id = :owner
                ORDER BY e.created_at DESC, e.id DESC LIMIT 1
                """).param("owner", owner)
                .query((rs, row) -> new LatestActivity(
                    "PAPER_CASH_EVENT", rs.getString("event_kind"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getObject("room_id", UUID.class))).list();
            if (!cashEvents.isEmpty() && (selected == null
                    || cashEvents.get(0).occurredAt().isAfter(selected.occurredAt()))) {
                selected = cashEvents.get(0);
            }
        }
        return selected;
    }

    static NextAction nextAction(String mode, Rooms rooms,
                                  BrokerConnections brokers) {
        if ("PAPER".equals(mode)) {
            if (rooms.count() == 0) {
                return new NextAction("CREATE_PAPER_ROOM", null, "CREATE_ROOM");
            }
            RoomPreview draft = rooms.featured().stream()
                .filter(room -> "DRAFT".equals(room.status()))
                .findFirst().orElse(null);
            if (draft != null) {
                return new NextAction("COMPLETE_PAPER_ROOM", draft.id(), "ROOM");
            }
            return new NextAction("REVIEW_PAPER_ROOM",
                rooms.featured().isEmpty() ? null : rooms.featured().get(0).id(), "ROOM");
        }
        if (brokers.connected() == 0) {
            return new NextAction(brokers.complete()
                ? "CONNECT_BROKER" : "VERIFY_BROKER", null, "BROKERS");
        }
        if (rooms.count() == 0) {
            return new NextAction("PLAN_LIVE_ROOM", null, "CREATE_ROOM");
        }
        return new NextAction("REVIEW_LIVE_ROOM",
            rooms.featured().isEmpty() ? null : rooms.featured().get(0).id(), "ROOM");
    }

    private String angelStatus(Authentication authentication) {
        try {
            var response = angel.status(authentication).getBody();
            if (response == null) return "UNAVAILABLE";
            return response.connected() ? "CONNECTED" : "NOT_CONNECTED";
        } catch (RuntimeException unavailable) {
            return "UNAVAILABLE";
        }
    }

    private String upstoxStatus(Authentication authentication) {
        try {
            var response = upstox.status(authentication).getBody();
            if (response == null) return "UNAVAILABLE";
            return response.connected() ? "CONNECTED" : "NOT_CONNECTED";
        } catch (RuntimeException unavailable) {
            return "UNAVAILABLE";
        }
    }

    private static Evidence evidence(MindReadinessService.Evidence source) {
        if (source == null) return new Evidence("UNAVAILABLE", null, null);
        return new Evidence(source.status(), source.asOf(), source.source());
    }

    private static RoomPreview mapRoom(java.sql.ResultSet rs)
            throws SQLException {
        return new RoomPreview(
            rs.getObject("id", UUID.class), rs.getString("name"),
            rs.getString("focus"), rs.getString("mode"),
            rs.getString("status"), rs.getLong("planned_budget_inr"),
            rs.getTimestamp("created_at").toInstant());
    }

    private UUID customer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || !token.getAuthorities().stream().anyMatch(
                    authority -> "ROLE_CUSTOMER".equals(authority.getAuthority()))) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Customer session required");
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
