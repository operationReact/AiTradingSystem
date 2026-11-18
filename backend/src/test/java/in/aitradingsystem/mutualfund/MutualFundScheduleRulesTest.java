package in.aitradingsystem.mutualfund;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MutualFundScheduleRulesTest {
    @Test void schedulesNextCycleWithoutSameMonthBackdating() {
        assertEquals(LocalDate.of(2026, 12, 10),
            MutualFundScheduleRules.firstDue(LocalDate.of(2026, 11, 18), 10));
        assertEquals(LocalDate.of(2026, 11, 18),
            MutualFundScheduleRules.firstDue(LocalDate.of(2026, 11, 18), 18));
    }

    @Test void onlyScheduledDayIsEligibleAndNoEarlierThanFirstDue() {
        assertFalse(MutualFundScheduleRules.isDue(
            LocalDate.of(2026, 11, 18), LocalDate.of(2026, 12, 18), 18));
        assertFalse(MutualFundScheduleRules.isDue(
            LocalDate.of(2026, 12, 19), LocalDate.of(2026, 12, 18), 18));
        assertTrue(MutualFundScheduleRules.isDue(
            LocalDate.of(2026, 12, 18), LocalDate.of(2026, 12, 18), 18));
    }

    @Test void rejectsInvalidFundsAndSpendingCaps() {
        assertThrows(IllegalArgumentException.class, () ->
            MutualFundScheduleRules.checkPlan("INE002A01018", "Equity fund", 50000, 100000, 10));
        assertThrows(IllegalArgumentException.class, () ->
            MutualFundScheduleRules.checkPlan("INF123456789", "Equity fund", 50000, 40000, 10));
        assertThrows(IllegalArgumentException.class, () ->
            MutualFundScheduleRules.checkPlan("INF123456789", "Equity fund", 50000, 100000, 31));
        assertDoesNotThrow(() ->
            MutualFundScheduleRules.checkPlan("INF123456789", "Equity fund", 50000, 100000, 10));
    }
}
