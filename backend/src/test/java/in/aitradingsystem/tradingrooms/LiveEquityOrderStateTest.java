package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LiveEquityOrderStateTest {
    @Test
    void unknownSubmissionCanOnlyAdvanceThroughReconciliationAndNeverRetry() {
        assertTrue(LiveEquityOrderState.SUBMITTING
            .mayTransitionTo(LiveEquityOrderState.UNKNOWN));
        assertTrue(LiveEquityOrderState.UNKNOWN
            .mayTransitionTo(LiveEquityOrderState.ACKNOWLEDGED));
        assertFalse(LiveEquityOrderState.UNKNOWN
            .mayTransitionTo(LiveEquityOrderState.SUBMITTING));
        assertTrue(LiveEquityOrderState.UNKNOWN.requiresReconciliation());
    }

    @Test
    void terminalStatesCannotBeRewritten() {
        for (var terminal : new LiveEquityOrderState[] {
                LiveEquityOrderState.FILLED,
                LiveEquityOrderState.REJECTED,
                LiveEquityOrderState.CANCELLED}) {
            assertFalse(terminal.requiresReconciliation());
            for (var next : LiveEquityOrderState.values()) {
                assertFalse(terminal.mayTransitionTo(next));
            }
        }
    }

    @Test
    void partialFillMustRemainReconciledUntilTerminal() {
        assertTrue(LiveEquityOrderState.PARTIALLY_FILLED.requiresReconciliation());
        assertTrue(LiveEquityOrderState.PARTIALLY_FILLED
            .mayTransitionTo(LiveEquityOrderState.FILLED));
        assertFalse(LiveEquityOrderState.PARTIALLY_FILLED
            .mayTransitionTo(LiveEquityOrderState.REJECTED));
    }
}
