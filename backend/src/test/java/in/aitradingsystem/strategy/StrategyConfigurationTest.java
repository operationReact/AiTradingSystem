package in.aitradingsystem.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class StrategyConfigurationTest {

    @Test
    void normalizesEquivalentConfigurationAndKeepsStableIdentity() {
        StrategyConfiguration first = config(new BigDecimal("2.0"), new BigDecimal("0.50"));
        StrategyConfiguration second = config(new BigDecimal("2.00"), new BigDecimal("0.500"));

        assertThat(first).isEqualTo(second);
        assertThat(first.identity()).isEqualTo("SMA_TREND_VOLUME:v1");
    }

    @Test
    void rejectsInvalidPeriodOrdering() {
        assertThatIllegalArgumentException().isThrownBy(() -> new StrategyConfiguration(
                "SMA_TREND_VOLUME", 1, 20, 20, new BigDecimal("2"), new BigDecimal("0.5"), 1440));
    }

    @Test
    void rejectsOutOfBoundsRiskRewardAndStopBuffer() {
        assertThatIllegalArgumentException().isThrownBy(() -> config(new BigDecimal("0.9"), new BigDecimal("0.5")));
        assertThatIllegalArgumentException().isThrownBy(() -> config(new BigDecimal("2"), BigDecimal.ZERO));
    }

    @Test
    void rejectsBlankIdentityAndNonPositiveVersion() {
        assertThatIllegalArgumentException().isThrownBy(() -> new StrategyConfiguration(
                "  ", 1, 5, 20, new BigDecimal("2"), new BigDecimal("0.5"), 1440));
        assertThatIllegalArgumentException().isThrownBy(() -> new StrategyConfiguration(
                "SMA_TREND_VOLUME", 0, 5, 20, new BigDecimal("2"), new BigDecimal("0.5"), 1440));
    }

    @Test
    void rejectsInvalidSignalAgeBounds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new StrategyConfiguration(
                "SMA_TREND_VOLUME", 1, 5, 20, new BigDecimal("2"), new BigDecimal("0.5"), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new StrategyConfiguration(
                "SMA_TREND_VOLUME", 1, 5, 20, new BigDecimal("2"), new BigDecimal("0.5"), 10081));
    }

    private StrategyConfiguration config(BigDecimal riskReward, BigDecimal stopBuffer) {
        return new StrategyConfiguration("SMA_TREND_VOLUME", 1, 5, 20, riskReward, stopBuffer, 1440);
    }
}
