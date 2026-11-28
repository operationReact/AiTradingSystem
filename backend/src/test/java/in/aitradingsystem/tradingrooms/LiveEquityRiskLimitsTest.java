package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LiveEquityRiskLimitsTest {
    @Test
    void zeroDefaultsLeaveDeploymentRiskPolicyUnconfigured() {
        assertThat(LiveEquityRiskLimits.disabled().configuredPolicy()).isEmpty();
        assertThat(new LiveEquityRiskLimits(0, 0, 0, 0).configuredPolicy()).isEmpty();
    }

    @Test
    void completeBoundedConfigurationCreatesAccountWidePolicy() {
        var limits = new LiveEquityRiskLimits(100_000, 25_000, 10, 2_000);

        assertThat(limits.configuredPolicy())
            .contains(new LiveEquityRiskPolicy(100_000, 25_000, 10, 2_000));
    }

    @Test
    void explicitZeroDailyLossLimitIsAConfiguredStopForEveryNewOrder() {
        var limits = new LiveEquityRiskLimits(100_000, 25_000, 10, 0);

        assertThat(limits.configuredPolicy())
            .contains(new LiveEquityRiskPolicy(100_000, 25_000, 10, 0));
    }

    @Test
    void outOfRangeValuesFailClosed() {
        assertThat(new LiveEquityRiskLimits(10_000_001, 1_000, 1, 500).configuredPolicy()).isEmpty();
        assertThat(new LiveEquityRiskLimits(10_000, 0, 1, 500).configuredPolicy()).isEmpty();
        assertThat(new LiveEquityRiskLimits(10_000, 1_000, 10_001, 500).configuredPolicy()).isEmpty();
        assertThat(new LiveEquityRiskLimits(10_000, 1_000, 1, 10_000_001).configuredPolicy()).isEmpty();
    }
}
