package in.aitradingsystem.risk;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.config.RiskProperties;
import in.aitradingsystem.domain.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RiskPolicyV1ParityTest {
    private static final Instant NOW = Instant.parse("2025-06-02T04:30:00Z");
    private static final RiskProperties PROPS = new RiskProperties(
            new BigDecimal("80000"), new BigDecimal("0.25"), new BigDecimal("1"), 3,
            new BigDecimal("0.0005"), new BigDecimal("0.001"), 15);

    private final RiskPolicy legacy = new RiskPolicy(PROPS);
    private final LegacyRiskPolicyAdapter adapter = new LegacyRiskPolicyAdapter(PROPS);

    @Test
    void existingV1FixturesRemainDecisionAndReasonCompatible() {
        List<RiskInput> fixtures = List.of(
                buy(10, NOW.minusSeconds(60), "490", "530", "80000", 0, 0, "0", true),
                buy(10, NOW.plusSeconds(60), "490", "530", "80000", 0, 0, "0", false),
                buy(10, NOW.minusSeconds(16 * 60), "490", "530", "80000", 0, 0, "0", false),
                buy(10, NOW.minusSeconds(60), "490", "530", "80000", 0, 0, "-800.00", false),
                buy(10, NOW.minusSeconds(60), "505", "530", "80000", 0, 0, "0", false),
                buy(10, NOW.minusSeconds(60), "480.75", "530", "80000", 0, 0, "0", false),
                buy(10, NOW.minusSeconds(60), "490", "530", "80000", 5, 1, "0", false),
                buy(10, NOW.minusSeconds(60), "490", "530", "80000", 0, 3, "0", false),
                buy(10, NOW.minusSeconds(60), "490", "530", "1000", 0, 0, "0", false),
                new RiskInput(Side.SELL, 10, new BigDecimal("500"), null, null,
                        NOW.minusSeconds(60), NOW, new BigDecimal("80000"), new BigDecimal("80000"),
                        5, 1, BigDecimal.ZERO, false),
                buy(10, NOW.minusSeconds(60), "490", "530", "80000", 0, 0, "0", false));

        for (RiskInput input : fixtures) {
            assertParity(input);
        }
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

        RiskPolicyEngine shadowEngine = new RiskPolicyEngine(parityRules());
        RiskDecisionV2 newDecision = shadowEngine.evaluate(context);

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

    private List<RiskRule> parityRules() {
        return Arrays.stream(RiskReason.values())
                .map(reason -> (RiskRule) new LegacyReasonParityRule(reason))
                .toList();
    }

    private RiskInput buy(int quantity, Instant signal, String stop, String target, String cash,
                          int held, int openCount, String pnlToday, boolean halted) {
        return new RiskInput(Side.BUY, quantity, new BigDecimal("500"), new BigDecimal(stop),
                new BigDecimal(target), signal, NOW, new BigDecimal("80000"), new BigDecimal(cash),
                held, openCount, new BigDecimal(pnlToday), halted);
    }

    private final class LegacyReasonParityRule implements RiskRule {
        private final RiskReason reason;
        private final RiskRuleCode code;

        private LegacyReasonParityRule(RiskReason reason) {
            this.reason = reason;
            this.code = switch (reason) {
                case EMERGENCY_STOP_ACTIVE -> RiskRuleCode.EMERGENCY_STOP;
                case SIGNAL_TIMESTAMP_INVALID -> RiskRuleCode.SIGNAL_TIMESTAMP;
                case STALE_SIGNAL -> RiskRuleCode.SIGNAL_FRESHNESS;
                case INVALID_PRICE_LEVELS -> RiskRuleCode.PRICE_LEVELS;
                case RISK_BUDGET_EXCEEDED -> RiskRuleCode.PER_TRADE_RISK_BUDGET;
                case INSUFFICIENT_CASH -> RiskRuleCode.CASH_AVAILABILITY;
                case SYMBOL_ALREADY_HELD -> RiskRuleCode.EXISTING_SYMBOL_POSITION;
                case MAX_OPEN_POSITIONS -> RiskRuleCode.MAX_OPEN_POSITIONS;
                case INSUFFICIENT_HOLDINGS -> RiskRuleCode.HOLDINGS_AVAILABILITY;
                case DAILY_LOSS_LIMIT_REACHED -> RiskRuleCode.DAILY_LOSS_LIMIT;
            };
        }

        @Override
        public RiskRuleCode code() {
            return code;
        }

        @Override
        public int sequence() {
            return code.ordinal();
        }

        @Override
        public RiskRuleResult evaluate(RiskContext context) {
            boolean rejected = legacy.evaluate(adapter.toLegacyInput(context)).reasons().contains(reason);
            return rejected
                    ? new RiskRuleResult(code, RiskOutcome.HARD_REJECT, reason.name(), null, null, null,
                            Map.of("parity", RiskPolicy.VERSION))
                    : new RiskRuleResult(code, RiskOutcome.PASS, code.name() + "_PASS", null, null, null,
                            Map.of("parity", RiskPolicy.VERSION));
        }
    }
}
