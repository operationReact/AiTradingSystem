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

class ExistingRiskRulesTest {
    private static final Instant NOW = Instant.parse("2025-06-02T04:30:00Z");
    private static final RiskProperties PROPS = new RiskProperties(
            new BigDecimal("80000"), new BigDecimal("0.25"), new BigDecimal("1"), 3,
            new BigDecimal("0.0005"), new BigDecimal("0.001"), 15);
    private final LegacyRiskPolicyAdapter adapter = new LegacyRiskPolicyAdapter(PROPS);

    @Test
    void extractedRulesHaveCanonicalStableOrder() {
        RiskPolicyEngine engine = new RiskPolicyEngine(rules());
        assertThat(engine.orderedRuleCodes()).containsExactly(
                RiskRuleCode.EMERGENCY_STOP,
                RiskRuleCode.SIGNAL_TIMESTAMP,
                RiskRuleCode.SIGNAL_FRESHNESS,
                RiskRuleCode.PRICE_LEVELS,
                RiskRuleCode.DAILY_LOSS_LIMIT,
                RiskRuleCode.PER_TRADE_RISK_BUDGET,
                RiskRuleCode.CASH_AVAILABILITY,
                RiskRuleCode.HOLDINGS_AVAILABILITY,
                RiskRuleCode.EXISTING_SYMBOL_POSITION,
                RiskRuleCode.MAX_OPEN_POSITIONS);
    }

    @Test
    void emergencyStopHardRejectsAndCannotBeSoftened() {
        RiskDecisionV2 decision = engine().evaluate(adapter.adapt(
                buy(10, NOW.minusSeconds(60), "490", "530", "80000", 0, 0, "0", true)));
        assertThat(rejected(decision)).containsExactly("EMERGENCY_STOP_ACTIVE");
        assertThat(decision.decisionClass()).isEqualTo(RiskDecisionClass.REJECTED);
    }

    @Test
    void futureSignalIsTimestampFailureButNotStaleFailure() {
        RiskDecisionV2 decision = engine().evaluate(adapter.adapt(
                buy(10, NOW.plusSeconds(60), "490", "530", "80000", 0, 0, "0", false)));
        assertThat(rejected(decision)).contains("SIGNAL_TIMESTAMP_INVALID").doesNotContain("STALE_SIGNAL");
    }

    @Test
    void staleSignalRecordsObservedAndConfiguredMinutes() {
        RiskDecisionV2 decision = engine().evaluate(adapter.adapt(
                buy(10, NOW.minusSeconds(16 * 60), "490", "530", "80000", 0, 0, "0", false)));
        RiskRuleResult result = result(decision, RiskRuleCode.SIGNAL_FRESHNESS);
        assertThat(result.reasonCode()).isEqualTo("STALE_SIGNAL");
        assertThat(result.observedValue()).isEqualByComparingTo("16");
        assertThat(result.limitValue()).isEqualByComparingTo("15");
    }

    @Test
    void invalidPriceLevelsRejectWithoutEmittingRiskBudgetFailure() {
        RiskDecisionV2 decision = engine().evaluate(adapter.adapt(
                buy(100, NOW.minusSeconds(60), "505", "530", "80000", 0, 0, "0", false)));
        assertThat(rejected(decision)).contains("INVALID_PRICE_LEVELS").doesNotContain("RISK_BUDGET_EXCEEDED");
    }

    @Test
    void dailyLossBoundaryRemainsInclusive() {
        RiskDecisionV2 decision = engine().evaluate(adapter.adapt(
                buy(10, NOW.minusSeconds(60), "490", "530", "80000", 0, 0, "-800.00", false)));
        RiskRuleResult result = result(decision, RiskRuleCode.DAILY_LOSS_LIMIT);
        assertThat(result.reasonCode()).isEqualTo("DAILY_LOSS_LIMIT_REACHED");
        assertThat(result.observedValue()).isEqualByComparingTo("-800.00");
        assertThat(result.limitValue()).isEqualByComparingTo("-800.00");
    }

    @Test
    void riskBudgetExactBoundaryPassesButAboveBoundaryRejects() {
        RiskDecisionV2 exact = engine().evaluate(adapter.adapt(
                buy(10, NOW.minusSeconds(60), "480.75", "530", "80000", 0, 0, "0", false)));
        RiskRuleResult exactResult = result(exact, RiskRuleCode.PER_TRADE_RISK_BUDGET);
        assertThat(exactResult.outcome()).isEqualTo(RiskOutcome.PASS);
        assertThat(exactResult.observedValue()).isEqualByComparingTo("200.00");
        assertThat(exactResult.limitValue()).isEqualByComparingTo("200.00");

        RiskDecisionV2 over = engine().evaluate(adapter.adapt(
                buy(100, NOW.minusSeconds(60), "490", "530", "80000", 0, 0, "0", false)));
        assertThat(result(over, RiskRuleCode.PER_TRADE_RISK_BUDGET).reasonCode())
                .isEqualTo("RISK_BUDGET_EXCEEDED");
    }

    @Test
    void cashAndHoldingsRulesRemainSideSpecific() {
        RiskDecisionV2 buy = engine().evaluate(adapter.adapt(
                buy(10, NOW.minusSeconds(60), "490", "530", "1000", 0, 0, "0", false)));
        assertThat(rejected(buy)).contains("INSUFFICIENT_CASH").doesNotContain("INSUFFICIENT_HOLDINGS");

        RiskInput sell = new RiskInput(Side.SELL, 10, new BigDecimal("500"), null, null,
                NOW.minusSeconds(60), NOW, new BigDecimal("80000"), new BigDecimal("80000"),
                5, 1, BigDecimal.ZERO, false);
        RiskDecisionV2 sellDecision = engine().evaluate(adapter.adapt(sell));
        assertThat(rejected(sellDecision)).contains("INSUFFICIENT_HOLDINGS").doesNotContain("INSUFFICIENT_CASH");
    }

    @Test
    void heldSymbolPreservesLegacyElseIfAndSuppressesMaxOpenPositionReason() {
        RiskDecisionV2 heldAndAtMax = engine().evaluate(adapter.adapt(
                buy(10, NOW.minusSeconds(60), "490", "530", "80000", 5, 3, "0", false)));
        assertThat(rejected(heldAndAtMax)).contains("SYMBOL_ALREADY_HELD").doesNotContain("MAX_OPEN_POSITIONS");

        RiskDecisionV2 noHoldingAtMax = engine().evaluate(adapter.adapt(
                buy(10, NOW.minusSeconds(60), "490", "530", "80000", 0, 3, "0", false)));
        assertThat(rejected(noHoldingAtMax)).contains("MAX_OPEN_POSITIONS").doesNotContain("SYMBOL_ALREADY_HELD");
    }

    @Test
    void nullSignalTimestampKeepsV1CompatibilityUntilProductionContextSwitch() {
        RiskDecisionV2 decision = engine().evaluate(adapter.adapt(
                buy(10, null, "490", "530", "80000", 0, 0, "0", false)));
        assertThat(result(decision, RiskRuleCode.SIGNAL_TIMESTAMP).outcome()).isEqualTo(RiskOutcome.PASS);
        assertThat(result(decision, RiskRuleCode.SIGNAL_FRESHNESS).outcome()).isEqualTo(RiskOutcome.PASS);
    }

    private RiskPolicyEngine engine() {
        return new RiskPolicyEngine(rules());
    }

    private List<RiskRule> rules() {
        return List.of(
                new EmergencyStopRule(),
                new SignalTimestampRule(),
                new SignalFreshnessRule(),
                new PriceLevelsRule(),
                new DailyLossRule(),
                new PerTradeRiskBudgetRule(),
                new CashAvailabilityRule(),
                new HoldingsAvailabilityRule(),
                new ExistingSymbolPositionRule(),
                new MaxOpenPositionsRule());
    }

    private static RiskRuleResult result(RiskDecisionV2 decision, RiskRuleCode code) {
        return decision.ruleResults().stream().filter(r -> r.code() == code).findFirst().orElseThrow();
    }

    private static List<String> rejected(RiskDecisionV2 decision) {
        return decision.ruleResults().stream()
                .filter(r -> r.outcome() == RiskOutcome.HARD_REJECT)
                .map(RiskRuleResult::reasonCode)
                .toList();
    }

    private static RiskInput buy(int quantity, Instant signal, String stop, String target, String cash,
                                 int held, int openCount, String pnlToday, boolean halted) {
        return new RiskInput(Side.BUY, quantity, new BigDecimal("500"), new BigDecimal(stop),
                new BigDecimal(target), signal, NOW, new BigDecimal("80000"), new BigDecimal(cash),
                held, openCount, new BigDecimal(pnlToday), halted);
    }
}
