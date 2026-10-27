package in.aitradingsystem.risk;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.config.RiskProperties;
import in.aitradingsystem.domain.Side;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RiskPolicyTest {
    private static final Instant NOW = Instant.parse("2025-06-02T04:30:00Z");
    private final RiskPolicy policy = new RiskPolicy(new RiskProperties(
        new BigDecimal("80000"), new BigDecimal("0.25"), new BigDecimal("1"), 3,
        new BigDecimal("0.0005"), new BigDecimal("0.001"), 15));

    private RiskInput buy(int qty, Instant signal, String stop, String target, String cash,
                          int held, int openCount, String pnlToday, boolean halted) {
        return new RiskInput(Side.BUY, qty, new BigDecimal("500"), new BigDecimal(stop),
            new BigDecimal(target), signal, NOW, new BigDecimal("80000"), new BigDecimal(cash),
            held, openCount, new BigDecimal(pnlToday), halted);
    }

    private RiskInput freshBuy(int qty) {
        return buy(qty, NOW.minusSeconds(60), "490", "530", "80000", 0, 0, "0", false);
    }

    @Test
    void approvesSmallBuyWithinBudget() {
        RiskDecision d = policy.evaluate(freshBuy(10));
        assertThat(d.approved()).isTrue();
        assertThat(d.reasons()).isEmpty();
        assertThat(d.riskBudget()).isEqualByComparingTo("200.00");
        assertThat(d.estimatedRisk()).isLessThanOrEqualTo(d.riskBudget());
    }

    @Test
    void rejectsRiskAboveBudget() {
        assertThat(policy.evaluate(freshBuy(100)).reasons()).contains(RiskReason.RISK_BUDGET_EXCEEDED);
    }

    @Test
    void rejectsStaleSignal() {
        RiskInput in = buy(10, NOW.minusSeconds(16 * 60), "490", "530", "80000", 0, 0, "0", false);
        assertThat(policy.evaluate(in).reasons()).contains(RiskReason.STALE_SIGNAL);
    }

    @Test
    void rejectsSignalFromTheFuture() {
        RiskInput in = buy(10, NOW.plusSeconds(60), "490", "530", "80000", 0, 0, "0", false);
        assertThat(policy.evaluate(in).reasons()).contains(RiskReason.SIGNAL_TIMESTAMP_INVALID);
    }

    @Test
    void emergencyStopBlocksNewOrders() {
        RiskInput in = buy(10, NOW.minusSeconds(60), "490", "530", "80000", 0, 0, "0", true);
        assertThat(policy.evaluate(in).reasons()).containsExactly(RiskReason.EMERGENCY_STOP_ACTIVE);
    }

    @Test
    void rejectsWhenCashIsInsufficient() {
        RiskInput in = buy(10, NOW.minusSeconds(60), "490", "530", "1000", 0, 0, "0", false);
        assertThat(policy.evaluate(in).reasons()).contains(RiskReason.INSUFFICIENT_CASH);
    }

    @Test
    void rejectsWhenMaxOpenPositionsReached() {
        RiskInput in = buy(10, NOW.minusSeconds(60), "490", "530", "80000", 0, 3, "0", false);
        assertThat(policy.evaluate(in).reasons()).contains(RiskReason.MAX_OPEN_POSITIONS);
    }

    @Test
    void rejectsWhenSymbolAlreadyHeld() {
        RiskInput in = buy(10, NOW.minusSeconds(60), "490", "530", "80000", 5, 1, "0", false);
        assertThat(policy.evaluate(in).reasons()).contains(RiskReason.SYMBOL_ALREADY_HELD);
    }

    @Test
    void rejectsWhenDailyLossLimitReached() {
        RiskInput in = buy(10, NOW.minusSeconds(60), "490", "530", "80000", 0, 0, "-800.00", false);
        assertThat(policy.evaluate(in).reasons()).contains(RiskReason.DAILY_LOSS_LIMIT_REACHED);
    }

    @Test
    void rejectsInvalidPriceLevels() {
        RiskInput in = buy(10, NOW.minusSeconds(60), "505", "530", "80000", 0, 0, "0", false);
        assertThat(policy.evaluate(in).reasons()).contains(RiskReason.INVALID_PRICE_LEVELS);
    }

    @Test
    void sellRequiresSufficientHoldings() {
        RiskInput in = new RiskInput(Side.SELL, 10, new BigDecimal("500"), null, null, NOW.minusSeconds(60), NOW,
            new BigDecimal("80000"), new BigDecimal("80000"), 5, 1, BigDecimal.ZERO, false);
        assertThat(policy.evaluate(in).reasons()).containsExactly(RiskReason.INSUFFICIENT_HOLDINGS);
    }

    @Test
    void approvesSellWithinHoldings() {
        RiskInput in = new RiskInput(Side.SELL, 10, new BigDecimal("500"), null, null, NOW.minusSeconds(60), NOW,
            new BigDecimal("80000"), new BigDecimal("80000"), 10, 1, BigDecimal.ZERO, false);
        assertThat(policy.evaluate(in).approved()).isTrue();
    }
}
