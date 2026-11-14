package in.aitradingsystem.mind;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MindDecisionHistoryServiceTest {
    private static final Instant SIGNAL = Instant.parse("2026-11-13T09:30:00Z");

    @Test
    void lineageRequiresDataCutoffNotBeforeTheSignal() {
        assertThat(MindDecisionHistoryService.lineageValid(SIGNAL, SIGNAL)).isTrue();
        assertThat(MindDecisionHistoryService.lineageValid(SIGNAL, SIGNAL.plusSeconds(60))).isTrue();
        // A cutoff before the signal would mean the decision used information from after the cutoff.
        assertThat(MindDecisionHistoryService.lineageValid(SIGNAL, SIGNAL.minusSeconds(1))).isFalse();
        assertThat(MindDecisionHistoryService.lineageValid(null, SIGNAL)).isFalse();
        assertThat(MindDecisionHistoryService.lineageValid(SIGNAL, null)).isFalse();
    }

    @Test
    void reasonsAreSplitOnThePersistedSeparatorAndBlanksDropped() {
        assertThat(MindDecisionHistoryService.splitReasons("TREND|VOLUME||RECLAIM"))
            .containsExactly("TREND", "VOLUME", "RECLAIM");
        assertThat(MindDecisionHistoryService.splitReasons(null)).isEmpty();
        assertThat(MindDecisionHistoryService.splitReasons("  ")).isEqualTo(List.of());
    }
}
