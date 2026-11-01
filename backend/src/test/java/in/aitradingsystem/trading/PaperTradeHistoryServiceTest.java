package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaperTradeHistoryServiceTest {
    @Test
    void closedTradesAreRecordedPerAccount() {
        var service = new PaperTradeHistoryService();
        var closed = new PaperPosition("o", "RELIANCE", "BUY", 10, money("100"), money("95"), money("110"),
            money("110"), BigDecimal.ZERO, money("100"), false);
        service.record("a", closed);
        assertThat(service.history("a")).hasSize(1);
        assertThat(service.history("a").getFirst().realizedPnl()).isEqualByComparingTo("100");
        assertThat(service.history("b")).isEmpty();
    }

    private BigDecimal money(String value) { return new BigDecimal(value); }
}
