package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaperPositionServiceTest {
    private final PaperPositionService service = new PaperPositionService();

    @Test
    void marksUnrealizedAndClosesAtTargetWithRealizedProfit() {
        var position = service.open(order("BUY"));
        var marked = service.mark(position, money("106"));
        assertThat(marked.unrealizedPnl()).isEqualByComparingTo("60");
        assertThat(service.shouldExit(marked, money("110"), false)).isTrue();
        var closed = service.close(marked, money("110"));
        assertThat(closed.open()).isFalse();
        assertThat(closed.realizedPnl()).isEqualByComparingTo("100");
    }

    @Test
    void stopLossAndEmergencyStopTriggerExit() {
        var position = service.open(order("BUY"));
        assertThat(service.shouldExit(position, money("95"), false)).isTrue();
        assertThat(service.shouldExit(position, money("100"), true)).isTrue();
    }

    private SimulatedPaperOrder order(String side) {
        return new SimulatedPaperOrder("order-1", "key-1", "RELIANCE", side, 10,
            money("100"), money("95"), money("110"), PaperOrderState.FILLED);
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
