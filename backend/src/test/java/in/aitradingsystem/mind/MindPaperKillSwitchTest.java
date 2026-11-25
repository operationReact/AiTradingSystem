package in.aitradingsystem.mind;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Drawdown kill switch boundary rules for new Mind PAPER entries (exits are never gated here). */
class MindPaperKillSwitchTest {

    private static final long INITIAL = 1_000_000_00L; // Rs 10,00,000 in paise

    @Test
    void noBlockWhenNoLossOrSmallLoss() {
        assertThat(MindPaperCycleService.drawdownReason(INITIAL, 0)).isNull();
        assertThat(MindPaperCycleService.drawdownReason(INITIAL, 1)).isNull();
        // 2.99% loss, just inside the 3% limit.
        assertThat(MindPaperCycleService.drawdownReason(INITIAL, -29_900_00L)).isNull();
    }

    @Test
    void blocksAtAndBeyondTheLimit() {
        // Exactly 3% is treated as reached (fail closed).
        assertThat(MindPaperCycleService.drawdownReason(INITIAL, -30_000_00L))
            .startsWith("DRAWDOWN_KILL_SWITCH").contains("3%");
        assertThat(MindPaperCycleService.drawdownReason(INITIAL, -50_000_00L)).startsWith("DRAWDOWN_KILL_SWITCH");
    }

    @Test
    void profitNeverBlocks() {
        assertThat(MindPaperCycleService.drawdownReason(INITIAL, 500_000_00L)).isNull();
    }
}
