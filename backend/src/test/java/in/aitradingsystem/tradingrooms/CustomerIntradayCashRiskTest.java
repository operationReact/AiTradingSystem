package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CustomerIntradayCashRiskTest {
    @Test
    void buyMustFitBothRoomBudgetAndBrokerFunds() {
        var blockers = CustomerExecutionReadinessService.assessOrderFunds(
            "BUY", new BigDecimal("12000.00"), new BigDecimal("15000.00"),
            new BigDecimal("10000.00"), BigDecimal.ZERO);
        assertThat(blockers).containsExactly("INSUFFICIENT_BROKER_AVAILABLE_FUNDS");
    }

    @Test
    void buyExceedingRoomBudgetIsBlockedEvenWhenBrokerHasFunds() {
        var blockers = CustomerExecutionReadinessService.assessOrderFunds(
            "BUY", new BigDecimal("12000.00"), new BigDecimal("10000.00"),
            new BigDecimal("25000.00"), BigDecimal.ZERO);
        assertThat(blockers).containsExactly("ORDER_EXCEEDS_REMAINING_ROOM_BUDGET");
    }

    @Test
    void unavailableBrokerFundsAreNeverAssumedToBeZeroOrIgnored() {
        var blockers = CustomerExecutionReadinessService.assessOrderFunds(
            "BUY", new BigDecimal("1000.00"), new BigDecimal("10000.00"), null,
            BigDecimal.ZERO);
        assertThat(blockers).containsExactly("BROKER_AVAILABLE_FUNDS_NOT_VERIFIED");
    }

    @Test
    void exactRoomAndFundsLimitsAreAllowedByThisIndividualGate() {
        var blockers = CustomerExecutionReadinessService.assessOrderFunds(
            "BUY", new BigDecimal("10000.00"), new BigDecimal("10000.00"),
            new BigDecimal("10000.00"), BigDecimal.ZERO);
        assertThat(blockers).isEmpty();
    }

    @Test
    void cumulativeBuysCannotSpendMoreThanRoomBudget() {
        var blockers = CustomerExecutionReadinessService.assessOrderFunds(
            "BUY", new BigDecimal("4000.00"), new BigDecimal("10000.00"),
            new BigDecimal("20000.00"), new BigDecimal("7000.00"));
        assertThat(blockers).containsExactly("ORDER_EXCEEDS_REMAINING_ROOM_BUDGET");
    }

    @Test
    void unreadableRoomLedgerFailsClosed() {
        var blockers = CustomerExecutionReadinessService.assessOrderFunds(
            "BUY", new BigDecimal("1000.00"), new BigDecimal("10000.00"),
            new BigDecimal("20000.00"), null);
        assertThat(blockers).containsExactly("ROOM_DAILY_EXPOSURE_NOT_VERIFIED");
    }
}
