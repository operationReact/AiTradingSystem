package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaperCapitalServiceTest {
    @Test
    void openCommitsCapitalAndProfitableCloseReleasesCapitalPlusPnl() {
        var service = new PaperCapitalService();
        var capital = service.initial(money("10000"));
        var position = new PaperPosition("o", "RELIANCE", "BUY", 10, money("100"), money("95"), money("110"),
            money("100"), BigDecimal.ZERO, BigDecimal.ZERO, true);
        var opened = service.onOpen(capital, position);
        assertThat(opened.availableCapital()).isEqualByComparingTo("9000");
        assertThat(opened.committedCapital()).isEqualByComparingTo("1000");

        var closed = new PaperPosition("o", "RELIANCE", "BUY", 10, money("100"), money("95"), money("110"),
            money("110"), BigDecimal.ZERO, money("100"), false);
        var finalCapital = service.onClose(opened, closed);
        assertThat(finalCapital.availableCapital()).isEqualByComparingTo("10100");
        assertThat(finalCapital.committedCapital()).isEqualByComparingTo("0");
        assertThat(finalCapital.realizedPnl()).isEqualByComparingTo("100");
    }

    private BigDecimal money(String value) { return new BigDecimal(value); }
}
