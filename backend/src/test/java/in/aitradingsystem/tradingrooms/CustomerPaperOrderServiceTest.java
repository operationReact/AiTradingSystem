package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Unit-level quote boundary and simulated-cost contract. Database
 *  ownership, constraints and row locks are separately defined in SQL. */
class CustomerPaperOrderServiceTest {
    @Test
    void fillsOnlyImmediatelyMarketablePaperLimitOrders() {
        assertThat(CustomerPaperOrderService.crossesLimit(
            "BUY",new BigDecimal("210.00"),new BigDecimal("210.00"))).isTrue();
        assertThat(CustomerPaperOrderService.crossesLimit(
            "BUY",new BigDecimal("210.05"),new BigDecimal("210.00"))).isFalse();
        assertThat(CustomerPaperOrderService.crossesLimit(
            "SELL",new BigDecimal("210.05"),new BigDecimal("210.00"))).isTrue();
        assertThat(CustomerPaperOrderService.crossesLimit(
            "SELL",new BigDecimal("209.95"),new BigDecimal("210.00"))).isFalse();
        assertThat(CustomerPaperOrderService.crossesLimit(
            "SOMETHING",new BigDecimal("200"),new BigDecimal("200"))).isFalse();
    }

    @Test
    void paiseAmountsNeverUseFloatingPointAndChargeNonzeroFees() {
        assertThat(CustomerPaperOrderService.toPaise(new BigDecimal("123.4567")))
            .isEqualTo(12346L);
        assertThat(CustomerPaperOrderService.toPaise(new BigDecimal("0.01")))
            .isEqualTo(1L);
        assertThat(CustomerPaperOrderService.simulatedFees(100L))
            .isEqualTo(100L);
        assertThat(CustomerPaperOrderService.simulatedFees(1_000_000L))
            .isEqualTo(500L);
    }
}
