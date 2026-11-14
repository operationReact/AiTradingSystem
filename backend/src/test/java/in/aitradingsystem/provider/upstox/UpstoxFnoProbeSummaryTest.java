package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpstoxFnoProbeSummaryTest {
    @Test void emptyIsObservedNotFailure() {
        var chain = new UpstoxAnalyticsSource.OptionChainSnapshot(
                "UPSTOX_ANALYTICS", Instant.now(), false, List.of());
        var receipt = UpstoxFnoProbeSummary.summarize(List.of(), chain);
        assertEquals("OBSERVED_EMPTY", receipt.status());
        assertEquals(0, receipt.quotedLegs());
        assertFalse(receipt.exchangeQuoteTimeVerified());
    }

    @Test void countsQuotedLegsAndOptionalGreeksWithoutExposingValues() {
        var call = new UpstoxAnalyticsSource.FnoOptionQuote(
                "NSE_FO|CE", BigDecimal.TEN, null, null, 0, 0, 0,
                null, BigDecimal.ONE, null, null, null);
        var put = new UpstoxAnalyticsSource.FnoOptionQuote(
                "NSE_FO|PE", BigDecimal.ONE, null, null, 0, 0, 0,
                null, null, null, null, null);
        var strike = new UpstoxAnalyticsSource.FnoOptionStrike(
                LocalDate.of(2026, 11, 26), "NSE_INDEX|Nifty 50",
                BigDecimal.valueOf(25000), BigDecimal.valueOf(25000), call, put);
        var chain = new UpstoxAnalyticsSource.OptionChainSnapshot(
                "UPSTOX_ANALYTICS", Instant.now(), false, List.of(strike));
        var receipt = UpstoxFnoProbeSummary.summarize(List.of(), chain);
        assertEquals("OBSERVED_NONEMPTY", receipt.status());
        assertEquals(1, receipt.strikes());
        assertEquals(2, receipt.quotedLegs());
        assertEquals(1, receipt.legsWithGreeks());
        assertFalse(receipt.toString().contains("25000"));
        assertFalse(receipt.exchangeQuoteTimeVerified());
    }

    @Test void malformedOrWrongSourceFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> UpstoxFnoProbeSummary.summarize(null, null));
        assertThrows(IllegalArgumentException.class,
                () -> UpstoxFnoProbeSummary.summarize(List.of(),
                        new UpstoxAnalyticsSource.OptionChainSnapshot(
                                "OTHER", Instant.now(), false, List.of())));
        assertEquals("SOURCE_UNAVAILABLE", UpstoxFnoProbeSummary.unavailable().status());
    }
}
