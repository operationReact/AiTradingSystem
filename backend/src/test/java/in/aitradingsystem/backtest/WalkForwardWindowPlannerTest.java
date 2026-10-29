package in.aitradingsystem.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class WalkForwardWindowPlannerTest {

    @Test
    void createsDeterministicRollingFoldsWithoutChangingConfiguration() {
        List<WalkForwardWindowPlanner.Window> windows = WalkForwardWindowPlanner.plan(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 9), 20, 5, 5);

        assertThat(windows).hasSize(4);
        assertThat(windows.get(0)).isEqualTo(new WalkForwardWindowPlanner.Window(
                1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 20),
                LocalDate.of(2026, 1, 21), LocalDate.of(2026, 1, 25)));
        assertThat(windows.get(3)).isEqualTo(new WalkForwardWindowPlanner.Window(
                4, LocalDate.of(2026, 1, 16), LocalDate.of(2026, 2, 4),
                LocalDate.of(2026, 2, 5), LocalDate.of(2026, 2, 9)));
    }

    @Test
    void refusesRangeWithoutOneCompleteFold() {
        assertThatThrownBy(() -> WalkForwardWindowPlanner.plan(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10), 8, 5, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete training/validation fold");
    }

    @Test
    void rejectsNonPositiveWindowSizes() {
        assertThatThrownBy(() -> WalkForwardWindowPlanner.plan(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 20), 10, 5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }
}
