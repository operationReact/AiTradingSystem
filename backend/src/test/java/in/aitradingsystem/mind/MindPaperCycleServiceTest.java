package in.aitradingsystem.mind;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class MindPaperCycleServiceTest {

    @Test
    void automaticSizingTakesTheTightestOfRiskExposureAndCash() {
        // Rs 20,000 wallet: 1% risk = Rs 200, 20% exposure = Rs 4,000.
        assertThat(MindPaperCycleService.size(2_000_000, 2_000_000, new BigDecimal("100.00"), new BigDecimal("99.50")))
            .isEqualTo(40); // exposure-bound (risk would allow 400)
        assertThat(MindPaperCycleService.size(2_000_000, 2_000_000, new BigDecimal("100.00"), new BigDecimal("90.00")))
            .isEqualTo(20); // risk-bound: 200 / 10
        assertThat(MindPaperCycleService.size(2_000_000, 50_000, new BigDecimal("100.00"), new BigDecimal("99.50")))
            .isEqualTo(4); // cash-bound incl. fee reserve
        assertThat(MindPaperCycleService.size(2_000_000, 2_000_000, new BigDecimal("100.00"), new BigDecimal("100.00")))
            .isZero(); // no positive per-share risk => no entry
        assertThat(MindPaperCycleService.size(2_000_000, 2_000_000, new BigDecimal("5000.00"), new BigDecimal("4990")))
            .isZero(); // one share would exceed the 20% exposure cap
        assertThat(MindPaperCycleService.size(2_000_000, 2_000_000, new BigDecimal("100.00"), null)).isZero();
    }

    @Test
    void limitsAreMarketableTickMultiplesAndCandlesMustBeRecent() {
        BigDecimal tick = new BigDecimal("0.05");
        assertThat(MindPaperCycleService.roundUp(new BigDecimal("105.81"), tick)).isEqualByComparingTo("105.85");
        assertThat(MindPaperCycleService.roundDown(new BigDecimal("105.81"), tick)).isEqualByComparingTo("105.80");
        assertThat(MindPaperCycleService.roundUp(new BigDecimal("105.85"), tick)).isEqualByComparingTo("105.85");
        assertThat(MindPaperCycleService.maxCandleAge(HistoricalCandleInterval.FIFTEEN_MINUTE)).isEqualTo(Duration.ofHours(1));
        assertThat(MindPaperCycleService.maxCandleAge(HistoricalCandleInterval.ONE_MINUTE)).isEqualTo(Duration.ZERO);
        assertThat(MindPaperCycleService.INTERVALS).doesNotContain(HistoricalCandleInterval.ONE_MINUTE);
    }

    @Test
    void decisionTrailRoundTripsWithoutInjection() {
        var stages = List.of(new MindPaperCycleService.Stage("QUOTE", "PASSED", "LTP 1|2\nx"),
            new MindPaperCycleService.Stage("SIZING", "BLOCKED", null));
        var decoded = MindPaperCycleService.decodeStages(MindPaperCycleService.encodeStages(stages));
        assertThat(decoded).extracting(MindPaperCycleService.Stage::code).containsExactly("QUOTE", "SIZING");
        assertThat(decoded.getFirst().detail()).isEqualTo("LTP 1/2 x");
    }

    @Test
    void displayedRiskPolicyMatchesEnforcedConstants() {
        var policy = MindPaperPortfolioService.POLICY;
        assertThat(MindPaperCycleService.RISK_BUDGET_FRACTION).isEqualByComparingTo("0.01");
        assertThat(policy.perTradeRiskBudget()).startsWith("1%");
        assertThat(MindPaperCycleService.MAX_EXPOSURE_FRACTION).isEqualByComparingTo("0.20");
        assertThat(policy.maxSingleOrderExposure()).startsWith("20%");
        assertThat(MindPaperCycleService.MAX_QUOTE_DIVERGENCE).isEqualByComparingTo("0.03");
        assertThat(policy.maxSignalQuoteDivergence()).startsWith("3%");
        assertThat(MindPaperPortfolioService.FRESH_MARK).isEqualTo(Duration.ofSeconds(15));
        assertThat(policy.maxQuoteAge()).startsWith("15 seconds");
        assertThat(policy.liveTrading()).contains("not authorized");
    }

    @Test
    void performanceUsesClosedTradesOnlyAndFlagsSmallSamples() {
        var p = MindPaperCycleService.performance(10, 4, 3, 3, List.of(500L, -200L, -400L, 300L));
        assertThat(p.closedTrades()).isEqualTo(4);
        assertThat(p.wins()).isEqualTo(2);
        assertThat(p.losses()).isEqualTo(2);
        assertThat(p.realizedPnlPaise()).isEqualTo(200L);
        assertThat(p.averagePnlPaise()).isEqualTo(50L);
        assertThat(p.maxDrawdownPaise()).isEqualTo(600L); // peak 500 -> trough -100
        assertThat(p.winRatePercent()).isEqualTo(50.0);
        assertThat(p.sampleTooSmall()).isTrue();
        var empty = MindPaperCycleService.performance(0, 0, 0, 0, List.of());
        assertThat(empty.winRatePercent()).isNull();
        assertThat(empty.averagePnlPaise()).isNull();
    }
}
