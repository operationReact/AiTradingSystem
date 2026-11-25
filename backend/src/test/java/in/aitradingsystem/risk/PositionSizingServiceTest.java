package in.aitradingsystem.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PositionSizingServiceTest {
    private final PositionSizingService service = new PositionSizingService();

    @Test
    void choosesMinimumHardCapAndUsesFloorDivision() {
        PositionSizeSuggestion result = service.suggest(request(), policy("1.00", "10000", "100", "100"),
                new PortfolioRiskState(new BigDecimal("100000"), new BigDecimal("100000"),
                        BigDecimal.ZERO, BigDecimal.ZERO, 1));

        assertThat(result.quantity()).isEqualTo(100);
        assertThat(result.limitingRule()).isEqualTo(RiskRuleCode.MAX_ORDER_NOTIONAL);
        assertThat(result.quantityCaps()).containsEntry(RiskRuleCode.PER_TRADE_RISK_BUDGET, 200)
                .containsEntry(RiskRuleCode.MAX_ORDER_NOTIONAL, 100);
        assertThat(result.estimatedNotional()).isEqualByComparingTo("10000.00");
        assertThat(result.estimatedFee()).isEqualByComparingTo("10.00");
        assertThat(result.estimatedRisk()).isEqualByComparingTo("510.00");
    }

    @Test
    void returnsZeroWhenRemainingSymbolExposureCannotFundOneShare() {
        PositionSizeSuggestion result = service.suggest(request(), policy("1.00", "100000", "100", "10"),
                new PortfolioRiskState(new BigDecimal("100000"), new BigDecimal("100000"),
                        BigDecimal.ZERO, new BigDecimal("9950"), 1));

        assertThat(result.quantity()).isZero();
        assertThat(result.limitingRule()).isEqualTo(RiskRuleCode.MAX_SYMBOL_EXPOSURE);
        assertThat(result.quantityCaps()).containsEntry(RiskRuleCode.MAX_SYMBOL_EXPOSURE, 0);
        assertThat(result.estimatedRisk()).isEqualByComparingTo("0.00");
    }

    @Test
    void floorsFinalSuggestionToInstrumentLotSize() {
        RiskPolicySnapshot policy = policy("1.00", "9000", "100", "100");
        PositionSizeSuggestion result = service.suggest(request(), policy,
                new PortfolioRiskState(new BigDecimal("100000"), new BigDecimal("100000"),
                        BigDecimal.ZERO, BigDecimal.ZERO, 25));

        assertThat(result.quantityCaps()).containsEntry(RiskRuleCode.MAX_ORDER_NOTIONAL, 90);
        assertThat(result.quantity()).isEqualTo(75);
        assertThat(result.limitingRule()).isEqualTo(RiskRuleCode.MAX_ORDER_NOTIONAL);
    }

    @Test
    void rejectsInvalidLongSetupInsteadOfReturningUnsafeSuggestion() {
        PositionSizeRequest invalid = new PositionSizeRequest(
                1L, "NSE", "TEST-EQ", new BigDecimal("100"), new BigDecimal("101"),
                new BigDecimal("110"), "TEST", TradingStyle.INTRADAY);
        assertThatThrownBy(() -> service.suggest(invalid, policy("1", "10000", "100", "100"),
                new PortfolioRiskState(new BigDecimal("100000"), new BigDecimal("100000"),
                        BigDecimal.ZERO, BigDecimal.ZERO, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stopLoss < entryPrice < targetPrice");
    }

    private static PositionSizeRequest request() {
        return new PositionSizeRequest(1L, "NSE", "TEST-EQ", new BigDecimal("100"),
                new BigDecimal("95"), new BigDecimal("110"), "TEST", TradingStyle.INTRADAY);
    }

    private static RiskPolicySnapshot policy(String riskPct, String maxNotional, String grossPct, String symbolPct) {
        return new RiskPolicySnapshot(
                UUID.randomUUID(), 7,
                new BigDecimal(riskPct), new BigDecimal("5"), 10,
                new BigDecimal("1"), new BigDecimal(maxNotional),
                new BigDecimal(grossPct), new BigDecimal(symbolPct), 100,
                30, 60, false, true, true,
                new BigDecimal("0.001"), BigDecimal.ZERO, "position-sizing-test");
    }
}
