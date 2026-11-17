package in.aitradingsystem.mind;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** STR-222 trailing exit: active only in profit, fires on a give-back from the high-water mark. */
class MindTrailingExitTest {

    private static final BigDecimal TRAIL = new BigDecimal("0.02");

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    void doesNotFireWhileNoProfitHasBeenMade() {
        assertThat(MindPaperCycleService.trailingExitHit(d("100"), d("100"), d("90"), TRAIL)).isFalse();
        assertThat(MindPaperCycleService.trailingExitHit(d("100"), d("99"), d("95"), TRAIL)).isFalse();
    }

    @Test
    void holdsWhileGiveBackIsWithinTheTrail() {
        // High 110, trail stop 107.8; price 108 is still above the stop.
        assertThat(MindPaperCycleService.trailingExitHit(d("100"), d("110"), d("108"), TRAIL)).isFalse();
    }

    @Test
    void firesAtOrBelowTheTrailStopWhileStillInProfit() {
        // High 110, trail stop 107.8; price 107.8 is exactly at the stop and above cost.
        assertThat(MindPaperCycleService.trailingExitHit(d("100"), d("110"), d("107.8"), TRAIL)).isTrue();
        assertThat(MindPaperCycleService.trailingExitHit(d("100"), d("110"), d("105"), TRAIL)).isTrue();
    }

    @Test
    void neverFiresOnALossEvenWhenTheTrailIsBreached() {
        // Went up to 110, then fell to 99 (below cost): the stop-loss path handles losses, not this rule.
        assertThat(MindPaperCycleService.trailingExitHit(d("100"), d("110"), d("99"), TRAIL)).isFalse();
    }

    @Test
    void missingInputsFailClosedToNoExit() {
        assertThat(MindPaperCycleService.trailingExitHit(null, d("110"), d("105"), TRAIL)).isFalse();
        assertThat(MindPaperCycleService.trailingExitHit(d("100"), null, d("105"), TRAIL)).isFalse();
        assertThat(MindPaperCycleService.trailingExitHit(d("0"), d("110"), d("105"), TRAIL)).isFalse();
    }
}
