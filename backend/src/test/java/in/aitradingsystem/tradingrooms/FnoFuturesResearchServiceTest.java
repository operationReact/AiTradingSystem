package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoClosingSnapshotStore;
import in.aitradingsystem.marketdata.FnoMarketDataProvider;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import in.aitradingsystem.provider.upstox.UpstoxFuturesInstrumentMaster.FuturesContract;
import in.aitradingsystem.tradingrooms.FnoFuturesContractRegistry.NearFar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class FnoFuturesResearchServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-23T05:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);
    private static final String NIFTY = "NSE_INDEX|Nifty 50";
    private static final FuturesContract NEAR = new FuturesContract("NSE_FO|SYN1001", NIFTY, "NIFTY",
        "NIFTY FUT 29 DEC 26", LocalDate.of(2026, 12, 29), 75);
    private static final FuturesContract FAR = new FuturesContract("NSE_FO|SYN1002", NIFTY, "NIFTY",
        "NIFTY FUT 26 JAN 27", LocalDate.of(2027, 1, 26), 75);

    private static MarketSessionCalendar calendar(boolean open) {
        MarketSessionCalendar c = mock(MarketSessionCalendar.class);
        when(c.isInsideFnoSession(NOW)).thenReturn(open);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<UpstoxFnoV3LiveFeedService> feed(UpstoxFnoV3LiveFeedService live) {
        ObjectProvider<UpstoxFnoV3LiveFeedService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(live);
        return p;
    }

    private static FnoFuturesContractRegistry registryWith(NearFar pair) {
        FnoFuturesContractRegistry registry = mock(FnoFuturesContractRegistry.class);
        when(registry.nearFar(eq(NIFTY), any())).thenReturn(Optional.ofNullable(pair));
        return registry;
    }

    private static FnoClosingSnapshotStore emptyStore() {
        FnoClosingSnapshotStore store = mock(FnoClosingSnapshotStore.class);
        when(store.pairRows(any(), any(), any(), any())).thenReturn(List.of());
        return store;
    }

    private static UpstoxFnoV3LiveFeedService liveWith(Map<String, UpstoxFnoV3LiveFeedService.PaperQuote> quotes) {
        UpstoxFnoV3LiveFeedService live = mock(UpstoxFnoV3LiveFeedService.class);
        quotes.forEach((k, q) -> when(live.eligiblePaperQuote(k)).thenReturn(Optional.of(q)));
        return live;
    }

    private static UpstoxFnoV3LiveFeedService.PaperQuote quote(String key, String bid, String ask) {
        return new UpstoxFnoV3LiveFeedService.PaperQuote(key, new BigDecimal(bid), new BigDecimal(ask),
            NOW.minusSeconds(2), NOW.minusSeconds(1));
    }

    private static Map<String, StrategyStatusView> byId(List<FnoFuturesResearchService.StrategyStatus> rows) {
        return rows.stream().collect(Collectors.toMap(FnoFuturesResearchService.StrategyStatus::strategyId,
            r -> new StrategyStatusView(r.status(), r.reasonCode(), r.contractsResolved(), r.freshQuotes(),
                r.historySessions(), r.inputState())));
    }

    record StrategyStatusView(String status, String reason, int contracts, int quotes, int history, String input) {}

    @Test
    void defaultFlagsBlockAllThreeOnRightsEvenWhenContractsExist() {
        var service = new FnoFuturesResearchService(feed(null), calendar(true),
            registryWith(new NearFar(NEAR, FAR)), emptyStore(), false, false, "");
        var rows = byId(service.evaluateAll(NOW));
        assertEquals("BLOCKED_RIGHTS", rows.get("strategy.STR-171").status());
        assertEquals("CALENDAR_SOURCE_RIGHTS_NOT_VERIFIED", rows.get("strategy.STR-171").reason());
        assertEquals("BLOCKED_RIGHTS", rows.get("strategy.STR-172").status());
    }

    @Test
    void verifiedRightsWithRealPairButNoLiveQuotesIsBlockedDataNotSignal() {
        var service = new FnoFuturesResearchService(feed(null), calendar(true),
            registryWith(new NearFar(NEAR, FAR)), emptyStore(), true, true, "");
        var calendarRow = byId(service.evaluateAll(NOW)).get("strategy.STR-171");
        assertEquals("BLOCKED_DATA", calendarRow.status());
        assertEquals("CALENDAR_QUOTE_INVALID_OR_STALE", calendarRow.reason());
        assertEquals(2, calendarRow.contracts());
        assertEquals(0, calendarRow.quotes());
        assertEquals("NEAR_FAR_CONTRACTS_RESOLVED", calendarRow.input());
    }

    @Test
    void freshLiveQuotesWithoutExplicitCostAssumptionsStillProduceNoSignal() {
        var live = liveWith(Map.of(
            NEAR.instrumentKey(), quote(NEAR.instrumentKey(), "24990", "25010"),
            FAR.instrumentKey(), quote(FAR.instrumentKey(), "25400", "25420")));
        var service = new FnoFuturesResearchService(feed(live), calendar(true),
            registryWith(new NearFar(NEAR, FAR)), emptyStore(), true, true, "");
        var row = byId(service.evaluateAll(NOW)).get("strategy.STR-171");
        assertEquals(2, row.quotes());
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("CALENDAR_COST_ASSUMPTIONS_INVALID", row.reason());
        assertEquals(FnoMarketDataProvider.UPSTOX_V3_LIVE.wireLabel(),
            service.evaluateAll(NOW).get(1).quoteSource());
    }

    @Test
    void rolloverWithoutThreeCompleteStoredSessionsStaysBlocked() {
        var store = emptyStore();
        var service = new FnoFuturesResearchService(feed(null), calendar(true),
            registryWith(new NearFar(NEAR, FAR)), store, true, true, "");
        var row = byId(service.evaluateAll(NOW)).get("strategy.STR-172");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals(0, row.history());
        verify(store).pairRows(eq(NEAR.instrumentKey()), eq(FAR.instrumentKey()), any(), eq(TODAY));
    }

    @Test
    void basisCarryStaysBlockedWhenDeliverableSpotIsNotVerified() {
        var service = new FnoFuturesResearchService(feed(null), calendar(true),
            registryWith(null), emptyStore(), true, true, "NSE_EQ|INE002A01018");
        var row = byId(service.evaluateAll(NOW)).get("strategy.STR-170");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("FUTURES_CONTRACT_OR_SPOT_IDENTITY_INVALID", row.reason());
        assertEquals("NO_VERIFIED_CONTRACT_PAIR", row.input());
    }

    @Test
    void spotQuotePresentStillLeavesStr170BlockedWhenDeliverabilityIsUnverified() {
        var spot = quote("NSE_EQ|INE002A01018", "2400", "2402");
        var live = liveWith(Map.of(NEAR.instrumentKey(), quote(NEAR.instrumentKey(), "2405", "2407")));
        when(live.eligibleResearchSpotQuote("NSE_EQ|INE002A01018")).thenReturn(Optional.of(spot));
        FnoFuturesContractRegistry registry = registryWith(new NearFar(NEAR, FAR));
        when(registry.nearFar(eq("NSE_EQ|INE002A01018"), any())).thenReturn(Optional.of(new NearFar(NEAR, FAR)));
        var service = new FnoFuturesResearchService(feed(live), calendar(true),
            registry, emptyStore(), true, true, "NSE_EQ|INE002A01018");
        var row = byId(service.evaluateAll(NOW)).get("strategy.STR-170");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("FUTURES_CONTRACT_OR_SPOT_IDENTITY_INVALID", row.reason());
        assertEquals(2, row.quotes());
        assertNotEquals("RESEARCH_CANDIDATE", row.status());
    }

    @Test
    void liveQuoteIsEmptyWithoutFeedBean() {
        var service = new FnoFuturesResearchService(feed(null), calendar(true),
            registryWith(null), emptyStore(), false, false, "");
        assertTrue(service.liveQuote("NSE_FO|SYN1001").isEmpty());
    }

    private static List<FnoClosingSnapshotStore.StoredRow> oiRows(int... days) {
        List<FnoClosingSnapshotStore.StoredRow> rows = new java.util.ArrayList<>();
        long[] values = {100000, 110000, 121000, 133000, 146000};
        for (int i = 0; i < days.length; i++) {
            LocalDate day = LocalDate.of(2026, 11, days[i]);
            var close = day.atTime(15, 40).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
            rows.add(new FnoClosingSnapshotStore.StoredRow(
                "UPSTOX_ANALYTICS", NEAR.instrumentKey(), day,
                close.minusSeconds(30), close.plusSeconds(30),
                new BigDecimal("25000"), new BigDecimal("25002"),
                values[i], 48000L));
        }
        return rows;
    }

    private static FnoFuturesContractRegistry currentRegistry() {
        FnoFuturesContractRegistry registry = registryWith(new NearFar(NEAR, FAR));
        when(registry.status()).thenReturn(new FnoFuturesContractRegistry.Status(
            "LOADED", null, NOW.minusSeconds(3600), "source-master-sha", 2, 1));
        return registry;
    }

    private static MarketSessionCalendar validCalendar() {
        MarketSessionCalendar c = calendar(false);
        when(c.isTradingDay(any(LocalDate.class))).thenAnswer(invocation -> {
            LocalDate d = invocation.getArgument(0);
            return d.getDayOfWeek().getValue() <= 5;
        });
        return c;
    }

    @Test
    void str173DirectAdapterEvaluatesFiveOriginalTimedSingleContractSessionsWithoutOrders() {
        var store = emptyStore();
        when(store.verifiedOiContractRows(eq(NEAR.instrumentKey()), eq(TODAY.minusDays(21)),
            eq(TODAY))).thenReturn(oiRows(16, 17, 18, 19, 20));
        var service = new FnoFuturesResearchService(feed(null), validCalendar(),
            currentRegistry(), store, true, true, "");
        var result = byId(service.evaluateAll(NOW)).get("strategy.STR-173");
        assertNotNull(result);
        assertEquals("RESEARCH_CANDIDATE", result.status());
        assertEquals("SUSTAINED_OPEN_INTEREST_RISE", result.reason());
        assertEquals(1, result.contracts());
        assertEquals(0, result.quotes());
        assertEquals(5, result.history());
        assertEquals("OI_SINGLE_CONTRACT_RESEARCH_ONLY", result.input());
        verify(store).verifiedOiContractRows(eq(NEAR.instrumentKey()),
            eq(TODAY.minusDays(21)), eq(TODAY));
    }

    @Test
    void str173ResearchRightsDeniedDoesNotReadHistoryOrMaster() {
        var registry = currentRegistry();
        var store = emptyStore();
        var service = new FnoFuturesResearchService(feed(null), validCalendar(),
            registry, store, false, false, "");
        var result = byId(service.evaluateAll(NOW)).get("strategy.STR-173");
        assertEquals("BLOCKED_RIGHTS", result.status());
        assertEquals("OI_SOURCE_RIGHTS_NOT_VERIFIED", result.reason());
        verify(registry, never()).status();
        verify(store, never()).verifiedOiContractRows(any(), any(), any());
    }

    @Test
    void str173RejectsMixedProviderRowsWithoutPickingConvenientSource() {
        var original = oiRows(16, 17, 18, 19, 20);
        var modified = new java.util.ArrayList<>(original);
        var last = original.get(4);
        modified.set(4, new FnoClosingSnapshotStore.StoredRow(
            "ANGEL_ONE_SMARTAPI", last.instrumentKey(), last.sessionDate(),
            last.exchangeAt(), last.observedAt(), last.bid(), last.ask(),
            last.openInterestUnits(), last.sessionVolumeUnits()));
        var store = emptyStore();
        when(store.verifiedOiContractRows(any(), any(), any())).thenReturn(modified);
        var result = byId(new FnoFuturesResearchService(feed(null), validCalendar(),
            currentRegistry(), store, true, true, "").evaluateAll(NOW))
            .get("strategy.STR-173");
        assertEquals("BLOCKED_DATA", result.status());
        assertEquals("OI_SOURCE_ROWS_AMBIGUOUS_OR_INCOMPLETE", result.reason());
    }

    @Test
    void str173RejectsMissingTradingDayInHistory() {
        var store = emptyStore();
        when(store.verifiedOiContractRows(any(), any(), any()))
            .thenReturn(oiRows(13, 16, 17, 19, 20));
        var result = byId(new FnoFuturesResearchService(feed(null), validCalendar(),
            currentRegistry(), store, true, true, "").evaluateAll(NOW))
            .get("strategy.STR-173");
        assertEquals("BLOCKED_DATA", result.status());
        assertEquals("OI_COMPLETED_SESSION_INVALID", result.reason());
    }

    @Test
    void str173RejectedSnapshotsAndSourceExceptionsDoNotExposeProviderDetails() {
        var store = emptyStore();
        when(store.verifiedOiContractRows(any(), any(), any()))
            .thenThrow(new IllegalStateException("private broker token"));
        var result = byId(new FnoFuturesResearchService(feed(null), validCalendar(),
            currentRegistry(), store, true, true, "").evaluateAll(NOW))
            .get("strategy.STR-173");
        assertEquals("BLOCKED_DATA", result.status());
        assertEquals("OI_HISTORY_READ_UNAVAILABLE", result.reason());
        assertFalse(result.reason().contains("broker"));
    }

}
