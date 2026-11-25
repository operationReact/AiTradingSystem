package in.aitradingsystem.risk;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.risk.rules.DuplicatePendingExposureRule;
import in.aitradingsystem.risk.rules.GrossPortfolioExposureRule;
import in.aitradingsystem.risk.rules.InstrumentEligibilityRule;
import in.aitradingsystem.risk.rules.MarketSessionRule;
import in.aitradingsystem.risk.rules.MarketStateFreshnessRule;
import in.aitradingsystem.risk.rules.MaxOrderNotionalRule;
import in.aitradingsystem.risk.rules.MaxTradesPerDayRule;
import in.aitradingsystem.risk.rules.MinimumRewardRiskRule;
import in.aitradingsystem.risk.rules.SymbolConcentrationRule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductionRiskRulesTest {
    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    @Test
    void newRulesUseCanonicalStableOrder() {
        RiskPolicyEngine engine = engine();
        assertThat(engine.orderedRuleCodes()).containsExactly(
                RiskRuleCode.INSTRUMENT_ELIGIBILITY,
                RiskRuleCode.MARKET_STATE_FRESHNESS,
                RiskRuleCode.MARKET_SESSION,
                RiskRuleCode.MIN_REWARD_RISK,
                RiskRuleCode.MAX_ORDER_NOTIONAL,
                RiskRuleCode.MAX_GROSS_EXPOSURE,
                RiskRuleCode.MAX_SYMBOL_EXPOSURE,
                RiskRuleCode.MAX_TRADES_PER_DAY,
                RiskRuleCode.DUPLICATE_PENDING_EXPOSURE);
    }

    @Test
    void instrumentEligibilityFailsClosed() {
        RiskDecisionV2 decision = engine().evaluate(context(false, NOW, MarketSessionState.OPEN,
                "500", "490", "530", "0", "0", 0, false, policy()));
        assertRejected(decision, RiskRuleCode.INSTRUMENT_ELIGIBILITY, "INSTRUMENT_NOT_ELIGIBLE");
    }

    @Test
    void marketStateRequiresPresentFreshNonFutureTimestamp() {
        RiskDecisionV2 missing = engine().evaluate(context(true, null, MarketSessionState.OPEN,
                "500", "490", "530", "0", "0", 0, false, policy()));
        assertRejected(missing, RiskRuleCode.MARKET_STATE_FRESHNESS, "MARKET_STATE_UNAVAILABLE");

        RiskDecisionV2 stale = engine().evaluate(context(true, NOW.minusSeconds(61), MarketSessionState.OPEN,
                "500", "490", "530", "0", "0", 0, false, policy()));
        RiskRuleResult staleResult = result(stale, RiskRuleCode.MARKET_STATE_FRESHNESS);
        assertThat(staleResult.reasonCode()).isEqualTo("MARKET_STATE_STALE");
        assertThat(staleResult.observedValue()).isEqualByComparingTo("61");
        assertThat(staleResult.limitValue()).isEqualByComparingTo("60");

        RiskDecisionV2 fresh = engine().evaluate(context(true, NOW.minusSeconds(60), MarketSessionState.OPEN,
                "500", "490", "530", "0", "0", 0, false, policy()));
        assertThat(result(fresh, RiskRuleCode.MARKET_STATE_FRESHNESS).outcome()).isEqualTo(RiskOutcome.PASS);
    }

    @Test
    void marketSessionFailsClosedUnlessExplicitlyDisabled() {
        RiskDecisionV2 closed = engine().evaluate(context(true, NOW, MarketSessionState.POST_CLOSE,
                "500", "490", "530", "0", "0", 0, false, policy()));
        assertRejected(closed, RiskRuleCode.MARKET_SESSION, "MARKET_SESSION_CLOSED");

        RiskPolicySnapshot disabled = policy(false, "1.50", "100000", "80", "25", 20);
        RiskDecisionV2 bypassedByPolicy = engine().evaluate(context(true, NOW, MarketSessionState.UNKNOWN,
                "500", "490", "530", "0", "0", 0, false, disabled));
        RiskRuleResult result = result(bypassedByPolicy, RiskRuleCode.MARKET_SESSION);
        assertThat(result.outcome()).isEqualTo(RiskOutcome.PASS);
        assertThat(result.details()).containsEntry("disabledByPolicy", true);
    }

    @Test
    void minimumRewardRiskUsesEffectiveFillAndRejectsBelowLimit() {
        RiskDecisionV2 weak = engine().evaluate(context(true, NOW, MarketSessionState.OPEN,
                "500", "490", "510", "0", "0", 0, false, policy()));
        RiskRuleResult result = result(weak, RiskRuleCode.MIN_REWARD_RISK);
        assertThat(result.outcome()).isEqualTo(RiskOutcome.HARD_REJECT);
        assertThat(result.reasonCode()).isEqualTo("MIN_REWARD_RISK_NOT_MET");
        assertThat(result.observedValue()).isLessThan(result.limitValue());
    }

    @Test
    void maxOrderNotionalUsesGrossBeforeFeesAndExactBoundaryPasses() {
        BigDecimal fill = new BigDecimal("500.5000");
        BigDecimal exact = fill.multiply(BigDecimal.TEN).setScale(2);
        RiskPolicySnapshot exactPolicy = policy(true, "1.50", exact.toPlainString(), "100", "100", 20);
        RiskDecisionV2 pass = engine().evaluate(context(true, NOW, MarketSessionState.OPEN,
                "500", "490", "530", "0", "0", 0, false, exactPolicy));
        assertThat(result(pass, RiskRuleCode.MAX_ORDER_NOTIONAL).outcome()).isEqualTo(RiskOutcome.PASS);

        RiskPolicySnapshot low = policy(true, "1.50", "5000.00", "100", "100", 20);
        RiskDecisionV2 rejected = engine().evaluate(context(true, NOW, MarketSessionState.OPEN,
                "500", "490", "530", "0", "0", 0, false, low));
        assertRejected(rejected, RiskRuleCode.MAX_ORDER_NOTIONAL, "MAX_ORDER_NOTIONAL_EXCEEDED");
    }

    @Test
    void exposureRulesRejectNewBuyButNeverBlockClosingSell() {
        RiskPolicySnapshot tight = policy(true, "1.50", "100000", "50", "20", 20);
        RiskDecisionV2 buy = engine().evaluate(context(true, NOW, MarketSessionState.OPEN,
                "500", "490", "530", "48000", "18000", 0, false, tight));
        assertRejected(buy, RiskRuleCode.MAX_GROSS_EXPOSURE, "MAX_GROSS_EXPOSURE_EXCEEDED");
        assertRejected(buy, RiskRuleCode.MAX_SYMBOL_EXPOSURE, "MAX_SYMBOL_EXPOSURE_EXCEEDED");

        RiskContext sellContext = context(Side.SELL, true, NOW, MarketSessionState.OPEN,
                "500", null, null, "60000", "25000", 0, false, tight);
        RiskDecisionV2 sell = engine().evaluate(sellContext);
        assertThat(result(sell, RiskRuleCode.MAX_GROSS_EXPOSURE).outcome()).isEqualTo(RiskOutcome.PASS);
        assertThat(result(sell, RiskRuleCode.MAX_SYMBOL_EXPOSURE).outcome()).isEqualTo(RiskOutcome.PASS);
    }

    @Test
    void tradesPerDayComparesPostTradeCountAndDuplicateExposureFailsClosed() {
        RiskPolicySnapshot limitThree = policy(true, "1.50", "100000", "100", "100", 3);
        RiskDecisionV2 third = engine().evaluate(context(true, NOW, MarketSessionState.OPEN,
                "500", "490", "530", "0", "0", 2, false, limitThree));
        assertThat(result(third, RiskRuleCode.MAX_TRADES_PER_DAY).outcome()).isEqualTo(RiskOutcome.PASS);

        RiskDecisionV2 fourth = engine().evaluate(context(true, NOW, MarketSessionState.OPEN,
                "500", "490", "530", "0", "0", 3, true, limitThree));
        assertRejected(fourth, RiskRuleCode.MAX_TRADES_PER_DAY, "MAX_TRADES_PER_DAY_EXCEEDED");
        assertRejected(fourth, RiskRuleCode.DUPLICATE_PENDING_EXPOSURE, "DUPLICATE_PENDING_EXPOSURE");
    }

    @Test
    void allProductionPolicyViolationsAreHardRejectsNeverApprovalRequired() {
        RiskPolicySnapshot tight = policy(true, "2.00", "1000", "10", "5", 1);
        RiskDecisionV2 decision = engine().evaluate(context(false, null, MarketSessionState.UNKNOWN,
                "500", "490", "505", "9000", "4500", 1, true, tight));
        assertThat(decision.ruleResults())
                .filteredOn(r -> r.outcome() != RiskOutcome.PASS)
                .isNotEmpty()
                .allMatch(r -> r.outcome() == RiskOutcome.HARD_REJECT);
        assertThat(decision.ruleResults()).noneMatch(r -> r.outcome() == RiskOutcome.APPROVAL_REQUIRED);
        assertThat(decision.decisionClass()).isEqualTo(RiskDecisionClass.REJECTED);
    }

    private static RiskPolicyEngine engine() {
        return new RiskPolicyEngine(List.of(
                new InstrumentEligibilityRule(),
                new MarketStateFreshnessRule(),
                new MarketSessionRule(),
                new MinimumRewardRiskRule(),
                new MaxOrderNotionalRule(),
                new GrossPortfolioExposureRule(),
                new SymbolConcentrationRule(),
                new MaxTradesPerDayRule(),
                new DuplicatePendingExposureRule()));
    }

    private static RiskPolicySnapshot policy() {
        return policy(true, "1.50", "100000", "80", "25", 20);
    }

    private static RiskPolicySnapshot policy(boolean enforceSession, String minRr, String maxNotional,
                                             String maxGrossPct, String maxSymbolPct, int maxTrades) {
        return new RiskPolicySnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000005"), 5,
                new BigDecimal("0.25"), new BigDecimal("1.00"), 3,
                new BigDecimal(minRr), new BigDecimal(maxNotional), new BigDecimal(maxGrossPct),
                new BigDecimal(maxSymbolPct), maxTrades, 15, 60, enforceSession, true, true,
                new BigDecimal("0.00050000"), new BigDecimal("0.00100000"), "test-v1");
    }

    private static RiskContext context(boolean eligible, Instant marketAsOf, MarketSessionState session,
                                       String reference, String stop, String target, String grossExposure,
                                       String symbolExposure, int tradesToday, boolean duplicate,
                                       RiskPolicySnapshot policy) {
        return context(Side.BUY, eligible, marketAsOf, session, reference, stop, target,
                grossExposure, symbolExposure, tradesToday, duplicate, policy);
    }

    private static RiskContext context(Side side, boolean eligible, Instant marketAsOf, MarketSessionState session,
                                       String reference, String stop, String target, String grossExposure,
                                       String symbolExposure, int tradesToday, boolean duplicate,
                                       RiskPolicySnapshot policy) {
        return new RiskContext(
                UUID.fromString("00000000-0000-0000-0000-000000000099"),
                "p05-pr04-test",
                ExecutionMode.PAPER,
                side,
                1L,
                "NSE",
                "TEST-EQ",
                "TEST_STRATEGY",
                TradingStyle.INTRADAY,
                10,
                new BigDecimal(reference),
                stop == null ? null : new BigDecimal(stop),
                target == null ? null : new BigDecimal(target),
                NOW.minusSeconds(30),
                marketAsOf,
                NOW,
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                BigDecimal.ZERO,
                side == Side.SELL ? 10 : 0,
                side == Side.SELL ? new BigDecimal("5000") : BigDecimal.ZERO,
                side == Side.SELL ? 1 : 0,
                new BigDecimal(grossExposure),
                new BigDecimal(symbolExposure),
                tradesToday,
                duplicate,
                false,
                session,
                eligible,
                policy);
    }

    private static RiskRuleResult result(RiskDecisionV2 decision, RiskRuleCode code) {
        return decision.ruleResults().stream().filter(r -> r.code() == code).findFirst().orElseThrow();
    }

    private static void assertRejected(RiskDecisionV2 decision, RiskRuleCode code, String reason) {
        RiskRuleResult result = result(decision, code);
        assertThat(result.outcome()).isEqualTo(RiskOutcome.HARD_REJECT);
        assertThat(result.reasonCode()).isEqualTo(reason);
    }
}
