package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LiveEquityRiskPolicyTest {
    // caps: account-wide BUY 100000, order value 50000, order qty 100, daily loss 5000
    private static final LiveEquityRiskPolicy POLICY =
        new LiveEquityRiskPolicy(100_000L, 50_000L, 100, 5_000L);

    @Test
    void absentPolicyFailsClosed() {
        var blockers = LiveEquityRiskPolicy.assessClaimRisk("BUY",
            new BigDecimal("1000"), 1, BigDecimal.ZERO, BigDecimal.ZERO, null);
        assertThat(blockers).containsExactly("ACCOUNT_WIDE_RISK_POLICY_NOT_CONFIGURED");
    }

    @Test
    void withinAllCapsAndLossVerifiedIsAllowedByThisGate() {
        var blockers = LiveEquityRiskPolicy.assessClaimRisk("BUY",
            new BigDecimal("10000"), 10, new BigDecimal("20000"), BigDecimal.ZERO, POLICY);
        assertThat(blockers).isEmpty();
    }

    @Test
    void orderQuantityCapIsEnforced() {
        var blockers = LiveEquityRiskPolicy.assessClaimRisk("BUY",
            new BigDecimal("10000"), 101, new BigDecimal("0"), BigDecimal.ZERO, POLICY);
        assertThat(blockers).contains("ORDER_EXCEEDS_MAX_QUANTITY");
    }

    @Test
    void orderValueCapIsEnforced() {
        var blockers = LiveEquityRiskPolicy.assessClaimRisk("BUY",
            new BigDecimal("50001"), 10, new BigDecimal("0"), BigDecimal.ZERO, POLICY);
        assertThat(blockers).contains("ORDER_EXCEEDS_MAX_ORDER_VALUE");
    }

    @Test
    void accountWideIntradayBudgetIsEnforcedAcrossAllRooms() {
        var blockers = LiveEquityRiskPolicy.assessClaimRisk("BUY",
            new BigDecimal("30000"), 10, new BigDecimal("80000"), BigDecimal.ZERO, POLICY);
        assertThat(blockers).contains("ORDER_EXCEEDS_ACCOUNT_WIDE_INTRADAY_BUDGET");
    }

    @Test
    void unreadableAccountWideExposureFailsClosedForBuys() {
        var blockers = LiveEquityRiskPolicy.assessClaimRisk("BUY",
            new BigDecimal("1000"), 1, null, BigDecimal.ZERO, POLICY);
        assertThat(blockers).contains("ACCOUNT_WIDE_INTRADAY_EXPOSURE_NOT_VERIFIED");
    }

    @Test
    void sellIsNotConstrainedByBuyBudgetButLossStillApplies() {
        var blockers = LiveEquityRiskPolicy.assessClaimRisk("SELL",
            new BigDecimal("30000"), 10, null, BigDecimal.ZERO, POLICY);
        // SELL ignores account-wide BUY budget, so a null BUY exposure is fine here.
        assertThat(blockers).doesNotContain("ACCOUNT_WIDE_INTRADAY_EXPOSURE_NOT_VERIFIED",
            "ORDER_EXCEEDS_ACCOUNT_WIDE_INTRADAY_BUDGET");
    }

    @Test
    void unverifiedDailyLossFailsClosed() {
        var blockers = LiveEquityRiskPolicy.assessClaimRisk("BUY",
            new BigDecimal("1000"), 1, BigDecimal.ZERO, null, POLICY);
        assertThat(blockers).contains("DAILY_REALIZED_LOSS_NOT_VERIFIED");
    }

    @Test
    void reachedDailyLossLimitBlocksNewOrders() {
        var blockers = LiveEquityRiskPolicy.assessClaimRisk("BUY",
            new BigDecimal("1000"), 1, BigDecimal.ZERO, new BigDecimal("5000"), POLICY);
        assertThat(blockers).contains("DAILY_REALIZED_LOSS_LIMIT_REACHED");
    }
}
