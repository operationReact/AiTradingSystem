package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaperTradeIntentServiceTest {
    private PaperTradeIntentService service() {
        return new PaperTradeIntentService(new PaperRiskDecisionService(new TradingSafetyPolicy()));
    }

    @Test
    void manualAndAutomatedIntentsUseSameRiskBoundary() {
        var service = service();
        assertThat(service.evaluate(intent("manual-1", TradeIntentSource.MANUAL), money("10000"), money("600"), false).accepted()).isTrue();
        assertThat(service.evaluate(intent("auto-1", TradeIntentSource.AI_AUTOMATION), money("10000"), money("600"), false).accepted()).isTrue();
    }

    @Test
    void emergencyStopBlocksBothIntentSources() {
        var service = service();
        assertThat(service.evaluate(intent("manual-stop", TradeIntentSource.MANUAL), money("10000"), money("600"), true).accepted()).isFalse();
        assertThat(service.evaluate(intent("auto-stop", TradeIntentSource.AI_AUTOMATION), money("10000"), money("600"), true).accepted()).isFalse();
    }

    @Test
    void duplicateAcceptedIntentIsRejected() {
        var service = service();
        var intent = intent("same-key", TradeIntentSource.AI_AUTOMATION);
        assertThat(service.evaluate(intent, money("10000"), money("600"), false).accepted()).isTrue();
        assertThat(service.evaluate(intent, money("10000"), money("600"), false).accepted()).isFalse();
    }

    private PaperTradeIntent intent(String key, TradeIntentSource source) {
        return new PaperTradeIntent(key, "RELIANCE", "BUY", 10, money("100"), money("95"), money("110"), source);
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
