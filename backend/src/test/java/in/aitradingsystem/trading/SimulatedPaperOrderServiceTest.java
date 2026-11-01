package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SimulatedPaperOrderServiceTest {
    @Test
    void retryReturnsSameSimulatedFillInsteadOfDuplicatingOrder() {
        var service = new SimulatedPaperOrderService();
        var intent = new PaperTradeIntent("ai:opp-1", "RELIANCE", "BUY", 10,
            new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"), TradeIntentSource.AI_AUTOMATION);

        var first = service.fill(intent);
        var retry = service.fill(intent);

        assertThat(first.orderId()).isEqualTo(retry.orderId());
        assertThat(first.state()).isEqualTo(PaperOrderState.FILLED);
        assertThat(retry.state()).isEqualTo(PaperOrderState.FILLED);
    }
}
