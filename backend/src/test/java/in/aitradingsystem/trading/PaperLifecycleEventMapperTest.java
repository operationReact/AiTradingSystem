package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaperLifecycleEventMapperTest {
    private final PaperLifecycleEventMapper mapper = new PaperLifecycleEventMapper();

    @Test
    void exposesReadableOpenClosedAndPausedStates() {
        var open = new PaperPosition("o", "RELIANCE", "BUY", 10, money("100"), money("95"), money("110"),
            money("106"), money("60"), BigDecimal.ZERO, true);
        assertThat(mapper.position("acct", open).state()).isEqualTo("Position open");

        var closed = new PaperPosition("o", "RELIANCE", "BUY", 10, money("100"), money("95"), money("110"),
            money("110"), BigDecimal.ZERO, money("100"), false);
        var event = mapper.position("acct", closed);
        assertThat(event.state()).isEqualTo("Closed");
        assertThat(event.message()).contains("Profit");
        assertThat(mapper.paused("acct", "RELIANCE").state()).isEqualTo("Trading paused");
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
