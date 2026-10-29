package in.aitradingsystem.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.domain.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiskPolicyEngineTest {
    @Test
    void evaluatesRulesInStableSequenceAndAggregatesApprovalRequired() {
        RiskRule later = rule(RiskRuleCode.MAX_OPEN_POSITIONS, 30, RiskOutcome.PASS);
        RiskRule approval = rule(RiskRuleCode.MAX_ORDER_NOTIONAL, 20, RiskOutcome.APPROVAL_REQUIRED);
        RiskRule first = rule(RiskRuleCode.EMERGENCY_STOP, 10, RiskOutcome.PASS);
        RiskPolicyEngine engine = new RiskPolicyEngine(List.of(later, approval, first));

        RiskDecisionV2 decision = engine.evaluate(context());

        assertThat(engine.orderedRuleCodes()).containsExactly(
                RiskRuleCode.EMERGENCY_STOP,
                RiskRuleCode.MAX_ORDER_NOTIONAL,
                RiskRuleCode.MAX_OPEN_POSITIONS);
        assertThat(decision.ruleResults()).extracting(RiskRuleResult::code)
                .containsExactly(RiskRuleCode.EMERGENCY_STOP, RiskRuleCode.MAX_ORDER_NOTIONAL, RiskRuleCode.MAX_OPEN_POSITIONS);
        assertThat(decision.decisionClass()).isEqualTo(RiskDecisionClass.APPROVAL_REQUIRED);
    }

    @Test
    void hardRejectDominatesApprovalRequiredWithoutShortCircuiting() {
        RiskPolicyEngine engine = new RiskPolicyEngine(List.of(
                rule(RiskRuleCode.EMERGENCY_STOP, 10, RiskOutcome.HARD_REJECT),
                rule(RiskRuleCode.MAX_ORDER_NOTIONAL, 20, RiskOutcome.APPROVAL_REQUIRED),
                rule(RiskRuleCode.MAX_OPEN_POSITIONS, 30, RiskOutcome.PASS)));

        RiskDecisionV2 decision = engine.evaluate(context());

        assertThat(decision.decisionClass()).isEqualTo(RiskDecisionClass.REJECTED);
        assertThat(decision.ruleResults()).hasSize(3);
    }

    @Test
    void identicalContextAndRulesProduceIdenticalDecision() {
        RiskPolicyEngine engine = new RiskPolicyEngine(List.of(
                rule(RiskRuleCode.EMERGENCY_STOP, 10, RiskOutcome.PASS),
                rule(RiskRuleCode.CASH_AVAILABILITY, 20, RiskOutcome.PASS)));
        RiskContext context = context();

        RiskDecisionV2 first = engine.evaluate(context);
        RiskDecisionV2 replay = engine.evaluate(context);

        assertThat(replay).isEqualTo(first);
        assertThat(first.decisionId()).isEqualTo(replay.decisionId());
        assertThat(first.effectiveFillPrice()).isEqualByComparingTo("500.500");
        assertThat(first.riskBudget()).isEqualByComparingTo("200.00");
    }

    @Test
    void rejectsDuplicateRuleCodesAndMismatchedResults() {
        assertThatThrownBy(() -> new RiskPolicyEngine(List.of(
                rule(RiskRuleCode.EMERGENCY_STOP, 10, RiskOutcome.PASS),
                rule(RiskRuleCode.EMERGENCY_STOP, 20, RiskOutcome.PASS))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate risk rule code");

        RiskRule mismatched = new RiskRule() {
            @Override public RiskRuleCode code() { return RiskRuleCode.EMERGENCY_STOP; }
            @Override public int sequence() { return 10; }
            @Override public RiskRuleResult evaluate(RiskContext context) {
                return RiskRuleResult.pass(RiskRuleCode.CASH_AVAILABILITY);
            }
        };
        assertThatThrownBy(() -> new RiskPolicyEngine(List.of(mismatched)).evaluate(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("code mismatch");
    }

    private static RiskRule rule(RiskRuleCode code, int sequence, RiskOutcome outcome) {
        return new RiskRule() {
            @Override public RiskRuleCode code() { return code; }
            @Override public int sequence() { return sequence; }
            @Override public RiskRuleResult evaluate(RiskContext context) {
                return new RiskRuleResult(code, outcome, code.name() + "_" + outcome.name(), null, null, null, Map.of());
            }
        };
    }

    private static RiskContext context() {
        Instant now = Instant.parse("2026-10-29T10:00:00Z");
        RiskPolicySnapshot policy = new RiskPolicySnapshot(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                1,
                new BigDecimal("0.25"),
                new BigDecimal("1.00"),
                3,
                new BigDecimal("2.0"),
                new BigDecimal("100000"),
                new BigDecimal("100"),
                new BigDecimal("50"),
                10,
                15,
                30,
                true,
                true,
                true,
                new BigDecimal("0.0005"),
                new BigDecimal("0.001"),
                "test-v1");
        return new RiskContext(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "corr-1",
                ExecutionMode.PAPER,
                Side.BUY,
                42L,
                "NSE",
                "RELIANCE",
                "OPENING_RANGE_BREAKOUT_V1",
                TradingStyle.INTRADAY,
                10,
                new BigDecimal("500"),
                new BigDecimal("490"),
                new BigDecimal("530"),
                now.minusSeconds(60),
                now.minusSeconds(1),
                now,
                new BigDecimal("80000"),
                new BigDecimal("80000"),
                new BigDecimal("80000"),
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                false,
                false,
                MarketSessionState.OPEN,
                true,
                policy);
    }
}
