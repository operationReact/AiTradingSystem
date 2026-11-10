package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure customer-journey checks; no real broker session or order capability. */
class CustomerHomeSummaryControllerTest {
    private static final UUID ROOM = UUID.fromString(
        "123e4567-e89b-12d3-a456-426614174000");

    private static CustomerHomeSummaryController.Rooms rooms(
            int count, int drafts, int funded) {
        return new CustomerHomeSummaryController.Rooms(
            count, drafts, funded, 20000, funded == 0 ? 0L : 2000000L,
            count == 0 ? List.of() : List.of(
                new CustomerHomeSummaryController.RoomPreview(
                    ROOM, "My room", "LONG_TERM", "PAPER",
                    drafts > 0 ? "DRAFT" : "PAPER_FUNDED", 20000,
                    Instant.parse("2026-11-10T10:00:00Z"))));
    }

    private static CustomerHomeSummaryController.BrokerConnections broker(
            String angel, String upstox) {
        int count = ("CONNECTED".equals(angel) ? 1 : 0)
            + ("CONNECTED".equals(upstox) ? 1 : 0);
        return new CustomerHomeSummaryController.BrokerConnections(
            angel, upstox, count,
            !"UNAVAILABLE".equals(angel) && !"UNAVAILABLE".equals(upstox));
    }

    @Test
    void newPaperUserStartsWithRoomPlanNotLiveOrder() {
        var action = CustomerHomeSummaryController.nextAction(
            "PAPER", rooms(0, 0, 0), broker("NOT_CONNECTED", "NOT_CONNECTED"));
        assertEquals("CREATE_PAPER_ROOM", action.code());
        assertEquals("CREATE_ROOM", action.destination());
        assertNull(action.roomId());
    }

    @Test
    void paperDraftIsPreferredOverBrokerSetup() {
        var action = CustomerHomeSummaryController.nextAction(
            "PAPER", rooms(1, 1, 0), broker("UNAVAILABLE", "UNAVAILABLE"));
        assertEquals("COMPLETE_PAPER_ROOM", action.code());
        assertEquals(ROOM, action.roomId());
        assertEquals("ROOM", action.destination());
    }

    @Test
    void fundedRoomRemainsSimulationOnly() {
        var action = CustomerHomeSummaryController.nextAction(
            "PAPER", rooms(1, 0, 1), broker("CONNECTED", "NOT_CONNECTED"));
        assertEquals("REVIEW_PAPER_ROOM", action.code());
        assertEquals(ROOM, action.roomId());
    }

    @Test
    void unavailableBrokerIsNeverLabeledDisconnected() {
        var action = CustomerHomeSummaryController.nextAction(
            "LIVE", rooms(0, 0, 0), broker("UNAVAILABLE", "NOT_CONNECTED"));
        assertEquals("VERIFY_BROKER", action.code());
        assertEquals("BROKERS", action.destination());
    }

    @Test
    void verifiedDisconnectedBrokerRequiresConnection() {
        var action = CustomerHomeSummaryController.nextAction(
            "LIVE", rooms(0, 0, 0), broker("NOT_CONNECTED", "NOT_CONNECTED"));
        assertEquals("CONNECT_BROKER", action.code());
    }

    @Test
    void connectedBrokerDoesNotAuthorizeOrders() {
        var action = CustomerHomeSummaryController.nextAction(
            "LIVE", rooms(0, 0, 0), broker("CONNECTED", "NOT_CONNECTED"));
        assertEquals("PLAN_LIVE_ROOM", action.code());
        assertEquals("CREATE_ROOM", action.destination());
    }
}
