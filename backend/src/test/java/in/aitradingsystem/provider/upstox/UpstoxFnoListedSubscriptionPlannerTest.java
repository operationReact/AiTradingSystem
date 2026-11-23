package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.*;

import in.aitradingsystem.provider.upstox.UpstoxFnoInstrumentUniverse.Contract;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Provider-shaped fixtures. These do NOT claim live NSE quote coverage. */
class UpstoxFnoListedSubscriptionPlannerTest {
    private static final String NIFTY = "NSE_INDEX|Nifty 50";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 12, 1);

    private static Contract c(String key, String type, LocalDate expiry, int strike) {
        return new Contract(key, NIFTY, "NIFTY", "NIFTY " + type, type,
            expiry, BigDecimal.valueOf(strike), 65, new BigDecimal("5"),
            new BigDecimal("100000"), false);
    }
    private static UpstoxAnalyticsSource.FnoOptionQuote q(String key) {
        return new UpstoxAnalyticsSource.FnoOptionQuote(key,
            new BigDecimal("120"), new BigDecimal("119"),
            new BigDecimal("121"), 20L, 1000L, 900L,
            null, null, null, null, null);
    }
    private static UpstoxAnalyticsSource.FnoOptionStrike row(int strike, String call, String put) {
        return new UpstoxAnalyticsSource.FnoOptionStrike(EXPIRY, NIFTY,
            BigDecimal.valueOf(strike), new BigDecimal("25620"), q(call), q(put));
    }
    private static UpstoxAnalyticsSource.OptionChainSnapshot chain() {
        return new UpstoxAnalyticsSource.OptionChainSnapshot("UPSTOX_ANALYTICS",
            Instant.parse("2026-11-23T08:00:00Z"), false,
            List.of(row(25000, "NSE_FO|201", "NSE_FO|202"),
                row(25600, "NSE_FO|203", "NSE_FO|204"),
                row(25700, "NSE_FO|205", "NSE_FO|206")));
    }
    private static List<Contract> master() {
        return List.of(
            c("NSE_FO|101", "FUT", EXPIRY, 0),
            c("NSE_FO|102", "FUT", EXPIRY.plusDays(28), 0),
            c("NSE_FO|201", "CE", EXPIRY, 25000),
            c("NSE_FO|202", "PE", EXPIRY, 25000),
            c("NSE_FO|203", "CE", EXPIRY, 25600),
            c("NSE_FO|204", "PE", EXPIRY, 25600),
            c("NSE_FO|205", "CE", EXPIRY, 25700),
            c("NSE_FO|206", "PE", EXPIRY, 25700));
    }

    @Test
    void nearFuturePairAndNearestAtmCallsAndPutsArePrioritized() {
        var keys = UpstoxFnoListedSubscriptionPlanner.plan(master(), chain(),
            NIFTY, EXPIRY, TODAY, 6);
        assertThat(keys).containsExactly("NSE_FO|101", "NSE_FO|102",
            "NSE_FO|203", "NSE_FO|204", "NSE_FO|205", "NSE_FO|206");
    }

    @Test
    void missingOrMismatchedOptionLegsNeverInventOtherStrikeKeys() {
        var subset = master().stream()
            .filter(c -> !"NSE_FO|204".equals(c.instrumentKey())).toList();
        var keys = UpstoxFnoListedSubscriptionPlanner.plan(subset, chain(),
            NIFTY, EXPIRY, TODAY, 6);
        assertThat(keys).containsExactly(
            "NSE_FO|101", "NSE_FO|102", "NSE_FO|205", "NSE_FO|206",
            "NSE_FO|201", "NSE_FO|202");
        assertThat(keys).doesNotContain("NSE_FO|203", "NSE_FO|204");
    }

    @Test
    void failsClosedOnSourceExpiryMismatchOrUnlistedUnderlying() {
        assertThatThrownBy(() -> UpstoxFnoListedSubscriptionPlanner.plan(
            master(), chain(), NIFTY, TODAY.minusDays(1), TODAY, 64))
            .hasMessage("FNO_SUBSCRIPTION_SOURCE_UNVERIFIED");
        assertThatThrownBy(() -> UpstoxFnoListedSubscriptionPlanner.plan(
            master(), chain(), "NSE_EQ|INE002A01018", EXPIRY, TODAY, 64))
            .hasMessage("FNO_SUBSCRIPTION_UNLISTED_CONTRACT");
        assertThatThrownBy(() -> UpstoxFnoListedSubscriptionPlanner.plan(
            master(), chain(), NIFTY, EXPIRY, TODAY, 65))
            .hasMessage("FNO_SUBSCRIPTION_SOURCE_UNVERIFIED");
        var falseLiveChain = new UpstoxAnalyticsSource.OptionChainSnapshot(
            "UPSTOX_ANALYTICS", Instant.now(), true, chain().strikes());
        assertThatThrownBy(() -> UpstoxFnoListedSubscriptionPlanner.plan(
            master(), falseLiveChain, NIFTY, EXPIRY, TODAY, 64))
            .hasMessage("FNO_SUBSCRIPTION_SOURCE_UNVERIFIED");
    }

    @Test
    void incompleteBodCannotPromoteWrongExchangeOrWrongSide() {
        var corrupt = new java.util.ArrayList<>(master());
        corrupt.add(new Contract("BSE_FO|777", NIFTY,
            "NIFTY", "NIFTY CE", "CE", EXPIRY, new BigDecimal("25620"),
            65, new BigDecimal("5"), null, false));
        assertThatThrownBy(() -> UpstoxFnoListedSubscriptionPlanner.plan(
            corrupt, chain(), NIFTY, EXPIRY, TODAY, 64))
            .hasMessage("FNO_SUBSCRIPTION_UNLISTED_CONTRACT");
    }
}
