package in.aitradingsystem.risk;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.config.RiskProperties;
import in.aitradingsystem.domain.Side;
import in.aitradingsystem.risk.rules.CashAvailabilityRule;
import in.aitradingsystem.risk.rules.DailyLossRule;
import in.aitradingsystem.risk.rules.EmergencyStopRule;
import in.aitradingsystem.risk.rules.ExistingSymbolPositionRule;
import in.aitradingsystem.risk.rules.HoldingsAvailabilityRule;
import in.aitradingsystem.risk.rules.MaxOpenPositionsRule;
import in.aitradingsystem.risk.rules.PerTradeRiskBudgetRule;
import in.aitradingsystem.risk.rules.PriceLevelsRule;
import in.aitradingsystem.risk.rules.SignalFreshnessRule;
import in.aitradingsystem.risk.rules.SignalTimestampRule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskPolicyV1ParityTest {
    private static final Instant NOW = Instant.parse("2025-06-02T04:30:00Z");
    private static final RiskProperties PROPS = new RiskProperties(
            new BigDecimal("80000"), new BigDecimal("0.25"), new BigDecimal("1"), 3,
            new BigDecimal("0.0005"), new BigDecimal("0.001"), 15);

    private final RiskPolicy legacy = new RiskPolicy(PROPS);
    private final LegacyRiskPolicyAdapter adapter = new LegacyRiskPolicyAdapter(PROPS);
    private final RiskPolicyEngine extracted = new RiskPolicyEngine(List.of(
            new EmergencyStopRule(),
            new SignalTimestampRule(),
            new SignalFreshnessRule(),
            new PriceLevelsRule(),
            new DailyLossRule(),
            new PerTradeRiskBudgetRule(),
            new CashAvailabilityRule(),
            new HoldingsAvailabilityRule(),
            new ExistingSymbolPositionRule(),
            new MaxOpenPositionsRule()));

    @Test
    void existingV1FixturesRemainDecisionAndReasonCompatible() {
        List<RiskInput> fixtures = List.of(
                buy(10, NOW.minusSeconds(60), "490", "530", "80000", 0, 0, "0", true),
                buy(10, NOW.plusSeconds(60), "490", "530", "80000", 0, 0, "0", false),
                buy(10, NOW.minusSeconds(16 * 60), "490", "530", "80000", 0, 0, "0", false),
                buy(10, NOW.minusSeconds(60), "490", "530", "80000", 0, 0, "-800.00", false),
                buy(10, NOW.minusSeconds(60), "505", "530", "80000", 0, 0, "0", false),
                buy(100, NOW.minusSeconds(60), "490", "530", "80000", 0, 0, "0", false),
                buy(10, NOW.minusSeconds(60), "480.75", "530", "80000", 0, 0, "0", false),
                buy(10, NOW.minusSeconds(60), "490", "530", "80000", 5, 3, "0", false),
                buy(10, NOW.minusSeconds(60), "490", "530", "80000", 0, 3, "0", false),
                buy(10, NOW.minusSeconds(60), "490", "530", "1000", 0, 0, "0", false),
                new RiskInput(Side.SELL, 10, new BigDecimal("500"), null, null,
                        NOW.minusSeconds(60), NOW, new BigDecimal("80000"), new BigDecimal("80000"),
                        5, 1, BigDecimal.ZERO, false),
                new RiskInput(Side.SELL, 10, new BigDecimal("500"), null, null,
                        NOW.minusSeconds(60), NOW, new BigDecimal("80000"), new BigDecimal("80000"),
                        10, 1, BigDecimal.ZERO, false),
                buy(10, null, "490", "530", "80000", 0, 0, "0", false),
                buy(10, NOW.minusSeconds(60), "490", "530", "80000", 0, 0, "0", false));

        for (RiskInput input : fixtures) assertParity(input);
    }

    @Test
    void riskBudgetExactBoundaryRemainsApproved() {
        RiskInput boundary = buy(10, NOW.minusSeconds(60), "480.75", "530", "80000", 0, 0, "0", false);
        RiskDecision legacyDecision = legacy.evaluate(boundary);

        assertThat(legacyDecision.estimatedRisk()).isEqualByComparingTo("200.00");
        assertThat(legacyDecision.riskBudget()).isEqualByComparingTo("200.00");
        assertThat(legacyDecision.approved()).isTrue();
        assertParity(boundary);
    }

    private void assertParity(RiskInput input) {
        RiskDecision oldDecision = legacy.evaluate(input);
        RiskContext context = adapter.adapt(input);
        assertThat(adapter.toLegacyInput(context)).isEqualTo(input);

        RiskDecisionV2 newDecision = extracted.evaluate(context);

        assertThat(newDecision.decisionClass() == RiskDecisionClass.APPROVED)
                .as("approval parity for %s", input)
                .isEqualTo(oldDecision.approved());
        assertThat(rejectedReasons(newDecision)).containsExactlyInAnyOrderElementsOf(oldDecision.reasons());
        assertThat(newDecision.effectiveFillPrice()).isEqualByComparingTo(oldDecision.fillPrice());
        assertThat(newDecision.grossNotional()).isEqualByComparingTo(oldDecision.grossAmount());
        assertThat(newDecision.estimatedFee()).isEqualByComparingTo(oldDecision.feeAmount());
        assertThat(newDecision.estimatedSlippage()).isEqualByComparingTo(oldDecision.slippageAmount());
        assertThat(newDecision.estimatedRisk()).isEqualByComparingTo(oldDecision.estimatedRisk());
        assertThat(newDecision.riskBudget()).isEqualByComparingTo(oldDecision.riskBudget());
    }

    private List<RiskReason> rejectedReasons(RiskDecisionV2 decision) {
        return decision.ruleResults().stream()
                .filter(result -> result.outcome() == RiskOutcome.HARD_REJECT)
                .map(result -> RiskReason.valueOf(result.reasonCode()))
                .toList();
    }

    private RiskInput buy(int quantity, Instant signal, String stop, String target, String cash,
                          int held, int openCount, String pnlToday, boolean halted) {
        return new RiskInput(Side.BUY, quantity, new BigDecimal("500"), new BigDecimal(stop),
                new BigDecimal(target), signal, NOW, new BigDecimal("80000"), new BigDecimal(cash),
                held, openCount, new BigDecimal(pnlToday), halted);
    }
}
