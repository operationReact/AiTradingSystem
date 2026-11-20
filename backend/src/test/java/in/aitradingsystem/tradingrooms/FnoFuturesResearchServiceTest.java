package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoMarketDataProvider;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class FnoFuturesResearchServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-23T05:00:00Z");

    @SuppressWarnings("unchecked")
    private static ObjectProvider<UpstoxFnoV3LiveFeedService> noFeed() {
        ObjectProvider<UpstoxFnoV3LiveFeedService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    private static MarketSessionCalendar calendar(boolean open) {
        MarketSessionCalendar c = mock(MarketSessionCalendar.class);
        when(c.isInsideFnoSession(NOW)).thenReturn(open);
        return c;
    }

    private static Map<String, String> reasons(List<FnoFuturesResearchService.StrategyStatus> rows) {
        return rows.stream().collect(java.util.stream.Collectors.toMap(
            FnoFuturesResearchService.StrategyStatus::strategyId,
            FnoFuturesResearchService.StrategyStatus::reasonCode));
    }

    @Test
    void defaultFlagsBlockAllThreeStrategiesOnRights() {
        var service = new FnoFuturesResearchService(noFeed(), calendar(true), false, false);
        var rows = service.evaluateAll(NOW);
        assertEquals(3, rows.size());
        for (var row : rows) assertEquals("BLOCKED_RIGHTS", row.status(), row.strategyId());
        assertEquals(Map.of(
            "strategy.STR-170", "FNO_SOURCE_USE_RIGHTS_NOT_VERIFIED",
            "strategy.STR-171", "CALENDAR_SOURCE_RIGHTS_NOT_VERIFIED",
            "strategy.STR-172", "ROLLOVER_SOURCE_RIGHTS_NOT_VERIFIED"), reasons(rows));
        assertTrue(rows.stream().allMatch(r -> "NONE".equals(r.quoteSource())));
    }

    @Test
    void verifiedRightsWithoutInputsReturnBlockedDataNotCandidates() {
        var service = new FnoFuturesResearchService(noFeed(), calendar(true), true, true);
        var rows = service.evaluateAll(NOW);
        for (var row : rows) {
            assertEquals("BLOCKED_DATA", row.status(), row.strategyId());
            assertNotEquals("RESEARCH_CANDIDATE", row.status());
        }
        assertEquals("FUTURES_CONTRACT_OR_SPOT_IDENTITY_INVALID", reasons(rows).get("strategy.STR-170"));
        assertEquals("CALENDAR_CONTRACT_PAIR_INVALID", reasons(rows).get("strategy.STR-171"));
        assertEquals("ROLLOVER_CONTRACT_PAIR_INVALID", reasons(rows).get("strategy.STR-172"));
    }

    @Test
    void closedSessionBlocksCalendarSpreadBeforeContractChecks() {
        var service = new FnoFuturesResearchService(noFeed(), calendar(false), true, true);
        var str171 = service.evaluateAll(NOW).stream()
            .filter(r -> r.strategyId().equals("strategy.STR-171")).findFirst().orElseThrow();
        assertEquals("BLOCKED_DATA", str171.status());
        assertEquals("FNO_EXCHANGE_SESSION_NOT_VERIFIED", str171.reasonCode());
    }

    @Test
    void v3QuoteIsLabelledWithCanonicalSourceAndRequiresLiveFeed() {
        assertTrue(new FnoFuturesResearchService(noFeed(), calendar(true), false, false)
            .v3Quote("NSE_FO|SYN1001").isEmpty());

        UpstoxFnoV3LiveFeedService live = mock(UpstoxFnoV3LiveFeedService.class);
        when(live.eligiblePaperQuote("NSE_FO|SYN1001")).thenReturn(Optional.of(
            new UpstoxFnoV3LiveFeedService.PaperQuote("NSE_FO|SYN1001",
                new BigDecimal("24990"), new BigDecimal("24992"),
                NOW.minusSeconds(2), NOW.minusSeconds(1))));
        @SuppressWarnings("unchecked")
        ObjectProvider<UpstoxFnoV3LiveFeedService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(live);
        var quote = new FnoFuturesResearchService(provider, calendar(true), false, false)
            .v3Quote("NSE_FO|SYN1001").orElseThrow();
        assertEquals(FnoMarketDataProvider.UPSTOX_V3_LIVE.wireLabel(), quote.source());
        assertEquals(new BigDecimal("24992"), quote.ask());
    }
}
