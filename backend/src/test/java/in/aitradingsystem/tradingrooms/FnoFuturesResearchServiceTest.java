package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoClosingSnapshotStore;
import in.aitradingsystem.marketdata.FnoCashFuturesFeasibilityStore;
import in.aitradingsystem.marketdata.FnoMarketDataProvider;
import in.aitradingsystem.marketdata.FnoVerifiedFinalPriceOiSessionStore;
import in.aitradingsystem.marketdata.FnoVerifiedCompletedOhlcvOiStore;
import in.aitradingsystem.marketdata.FnoIndexHedgeResearchStore;
import in.aitradingsystem.marketdata.FnoVerifiedIntradayLiquidityStore;
import in.aitradingsystem.marketdata.FnoVerifiedExpiryBasisPairStore;
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
        assertEquals("OI_RESEARCH_ONLY_NO_ORDER_PERMISSION", result.input());
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


    private static List<FnoVerifiedFinalPriceOiSessionStore.FinalSession> finalRows(int... dates) {
        String[] closes = {"100", "104", "108", "112", "116"};
        long[] oi = {100000L, 95000L, 90000L, 85000L, 80000L};
        var result = new java.util.ArrayList<FnoVerifiedFinalPriceOiSessionStore.FinalSession>();
        for (int i = 0; i < dates.length; i++) {
            LocalDate date = LocalDate.of(2026, 11, dates[i]);
            Instant close = date.atTime(15, 40).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
            result.add(new FnoVerifiedFinalPriceOiSessionStore.FinalSession(
                "UPSTOX_ANALYTICS", NEAR.instrumentKey(), NEAR.underlyingKey(),
                NEAR.expiry(), NEAR.lotSize(), date,
                new BigDecimal(closes[i]), close.plusSeconds(120),
                oi[i], 50000L, close.plusSeconds(900), close));
        }
        return result;
    }

    private static FnoFuturesResearchService verified174(
            FnoVerifiedFinalPriceOiSessionStore finalStore) {
        return new FnoFuturesResearchService(feed(null), validCalendar(),
            currentRegistry(), emptyStore(), finalStore, true, true, true, "");
    }

    @Test
    void str174EvaluatesFiveGenuineFinalCloseOiReceiptShapesButNeverOrders() {
        var receipts = mock(FnoVerifiedFinalPriceOiSessionStore.class);
        when(receipts.readExactContract(eq(NEAR.instrumentKey()),
            eq(TODAY.minusDays(21)), eq(TODAY), eq(NOW)))
            .thenReturn(finalRows(16, 17, 18, 19, 20));
        var row = byId(verified174(receipts).evaluateAll(NOW)).get("strategy.STR-174");
        assertEquals("RESEARCH_CANDIDATE", row.status());
        assertEquals("PRICE_RISING_WITH_FALLING_OI", row.reason());
        assertEquals(1, row.contracts());
        assertEquals(5, row.history());
        assertEquals(0, row.quotes());
        assertEquals("PRICE_OI_FINAL_RECEIPT_RESEARCH_ONLY", row.input());
        verify(receipts, times(1)).readExactContract(eq(NEAR.instrumentKey()),
            eq(TODAY.minusDays(21)), eq(TODAY), eq(NOW));
    }

    @Test
    void str174DefaultsToRightsDeniedAndNeverReadsFinalSessionSource() {
        var receipts = mock(FnoVerifiedFinalPriceOiSessionStore.class);
        var service = new FnoFuturesResearchService(feed(null), validCalendar(),
            currentRegistry(), emptyStore(), receipts, true, true, false, "");
        var row = byId(service.evaluateAll(NOW)).get("strategy.STR-174");
        assertEquals("BLOCKED_RIGHTS", row.status());
        assertEquals("PRICE_OI_SOURCE_RIGHTS_NOT_VERIFIED", row.reason());
        verifyNoInteractions(receipts);
    }

    @Test
    void str174MissingFinalPriceReceiptFailsClosedRatherThanUsingBidAsk() {
        var receipts = mock(FnoVerifiedFinalPriceOiSessionStore.class);
        var row = byId(verified174(receipts).evaluateAll(NOW)).get("strategy.STR-174");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("PRICE_OI_FIVE_VERIFIED_FINAL_SESSIONS_MISSING", row.reason());
        assertEquals(0, row.quotes());
    }

    @Test
    void str174RejectsNonExchangeCloseTimestampAndUnverifiedFinalPrice() {
        var vals = new java.util.ArrayList<>(finalRows(16, 17, 18, 19, 20));
        var original = vals.get(4);
        vals.set(4, new FnoVerifiedFinalPriceOiSessionStore.FinalSession(
            original.providerLabel(), original.instrumentKey(), original.underlyingKey(),
            original.expiry(), original.lotSize(), original.sessionDate(),
            original.finalClosePrice(), original.finalPriceFirstAvailableAt(),
            original.finalOpenInterestUnits(), original.tradedVolumeUnits(),
            original.finalizedOiFirstAvailableAt(), original.verifiedExchangeCloseAt().minusSeconds(600)));
        var receipts = mock(FnoVerifiedFinalPriceOiSessionStore.class);
        when(receipts.readExactContract(any(), any(), any(), any())).thenReturn(vals);
        var row = byId(verified174(receipts).evaluateAll(NOW)).get("strategy.STR-174");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("PRICE_OI_FINAL_ROWS_INCOMPLETE_OR_AMBIGUOUS", row.reason());
    }

    @Test
    void str174RejectsCrossProviderHistoryAndMissingMarketDay() {
        var mixed = new java.util.ArrayList<>(finalRows(16, 17, 18, 19, 20));
        var original = mixed.get(4);
        mixed.set(4, new FnoVerifiedFinalPriceOiSessionStore.FinalSession(
            "ANGEL_ONE_SMARTAPI", original.instrumentKey(), original.underlyingKey(),
            original.expiry(), original.lotSize(), original.sessionDate(),
            original.finalClosePrice(), original.finalPriceFirstAvailableAt(),
            original.finalOpenInterestUnits(), original.tradedVolumeUnits(),
            original.finalizedOiFirstAvailableAt(), original.verifiedExchangeCloseAt()));
        var receipts = mock(FnoVerifiedFinalPriceOiSessionStore.class);
        when(receipts.readExactContract(any(), any(), any(), any())).thenReturn(mixed);
        assertEquals("PRICE_OI_FINAL_ROWS_INCOMPLETE_OR_AMBIGUOUS",
            byId(verified174(receipts).evaluateAll(NOW))
            .get("strategy.STR-174").reason());
        when(receipts.readExactContract(any(), any(), any(), any()))
            .thenReturn(finalRows(13, 16, 17, 19, 20));
        assertEquals("PRICE_OI_FINAL_SESSION_INVALID",
            byId(verified174(receipts).evaluateAll(NOW))
            .get("strategy.STR-174").reason());
    }

    @Test
    void str174SanitizesDatasourceFailureInsteadOfLeakingSensitiveProviderText() {
        var receipts = mock(FnoVerifiedFinalPriceOiSessionStore.class);
        when(receipts.readExactContract(any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("private-provider-secret"));
        var row = byId(verified174(receipts).evaluateAll(NOW)).get("strategy.STR-174");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("PRICE_OI_FINAL_SESSION_READ_UNAVAILABLE", row.reason());
        assertFalse(row.reason().contains("secret"));
    }


    private static final String CASH_EQUITY = "NSE_EQ|INE002A01018";
    private static final String TEST_SCOPE = "private_testing_01";
    private static final FuturesContract CASH_NEAR = new FuturesContract(
        "NSE_FO|SYN_STOCK_DEC", CASH_EQUITY, "TEST_STOCK", "TEST STOCK FUT DEC26",
        LocalDate.of(2026, 12, 29), 250);
    private static final FuturesContract CASH_FAR = new FuturesContract(
        "NSE_FO|SYN_STOCK_JAN", CASH_EQUITY, "TEST_STOCK", "TEST STOCK FUT JAN27",
        LocalDate.of(2027, 1, 26), 250);

    private static FnoFuturesContractRegistry cashRegistry() {
        var registry = currentRegistry();
        when(registry.nearFar(eq(CASH_EQUITY), any()))
            .thenReturn(Optional.of(new NearFar(CASH_NEAR, CASH_FAR)));
        return registry;
    }

    private static FnoCashFuturesFeasibilityStore.Proof cashProof() {
        return new FnoCashFuturesFeasibilityStore.Proof(
            "UPSTOX_V3", TEST_SCOPE, CASH_EQUITY, CASH_NEAR.instrumentKey(),
            CASH_NEAR.expiry(), CASH_NEAR.lotSize(),
            600L, 500L, new BigDecimal("100.10"), new BigDecimal("103.50"),
            NOW.minusSeconds(2), NOW.minusSeconds(1),
            new FnoFuturesBasisCarryV1.Assumptions(
                new BigDecimal("600"), BigDecimal.ZERO, new BigDecimal("100"),
                new BigDecimal("20"), new BigDecimal("25")),
            new FnoCashFuturesArbitrageResearchV1.Capital(
                new BigDecimal("50000"), new BigDecimal("8000"),
                new BigDecimal("3000"), new BigDecimal("2000"), true, true, true),
            new FnoCashFuturesArbitrageResearchV1.AdditionalCosts(
                new BigDecimal("0.03"), new BigDecimal("50"), new BigDecimal("20"),
                new BigDecimal("15"), new BigDecimal("250"),
                true, true, true, true));
    }

    private static UpstoxFnoV3LiveFeedService matchedCashQuotes() {
        var live = liveWith(Map.of(CASH_NEAR.instrumentKey(),
            quote(CASH_NEAR.instrumentKey(), "103.50", "103.70")));
        when(live.eligibleResearchSpotQuote(CASH_EQUITY))
            .thenReturn(Optional.of(quote(CASH_EQUITY, "100", "100.10")));
        return live;
    }

    private static FnoFuturesResearchService cashService(
            FnoCashFuturesFeasibilityStore receipts, boolean cashRights,
            boolean sessionOpen, String accountScope, UpstoxFnoV3LiveFeedService live) {
        return new FnoFuturesResearchService(feed(live), calendar(sessionOpen),
            cashRegistry(), emptyStore(), null, receipts, true, true,
            false, cashRights, accountScope, CASH_EQUITY);
    }

    @Test
    void str175IntegratesVerifiedSameAccountDepthCapitalAndCostsWithoutOrderExecution() {
        var store = mock(FnoCashFuturesFeasibilityStore.class);
        when(store.currentSingleProof(eq(TEST_SCOPE), eq(CASH_EQUITY),
            eq(CASH_NEAR.instrumentKey()), eq(CASH_NEAR.expiry()), eq(250), eq(NOW)))
            .thenReturn(Optional.of(cashProof()));
        var result = byId(cashService(store, true, true, TEST_SCOPE,
            matchedCashQuotes()).evaluateAll(NOW)).get("strategy.STR-175");
        assertNotNull(result);
        assertEquals("RESEARCH_CANDIDATE", result.status());
        assertEquals("STRESSED_CASH_FUT_RESEARCH_CANDIDATE", result.reason());
        assertEquals(1, result.contracts());
        assertEquals(2, result.quotes());
        assertEquals(0, result.history());
        assertEquals("CASH_FUT_VERIFIED_FEASIBILITY_RECEIPT_RESEARCH_ONLY", result.input());
        verify(store).currentSingleProof(eq(TEST_SCOPE), eq(CASH_EQUITY),
            eq(CASH_NEAR.instrumentKey()), eq(CASH_NEAR.expiry()), eq(250), eq(NOW));
        // The integration exposes only research status, never capital or account data.
        assertFalse(result.input().contains(TEST_SCOPE));
    }

    @Test
    void str175DefaultRightsDeniedDoesNotFetchBrokerEvidence() {
        var store = mock(FnoCashFuturesFeasibilityStore.class);
        var result = byId(cashService(store, false, true, TEST_SCOPE,
            matchedCashQuotes()).evaluateAll(NOW)).get("strategy.STR-175");
        assertEquals("BLOCKED_RIGHTS", result.status());
        assertEquals("CASH_FUT_SOURCE_RIGHTS_NOT_VERIFIED", result.reason());
        verifyNoInteractions(store);
    }

    @Test
    void str175RefusesWrongAccountAndMissingMarginReceipts() {
        var store = mock(FnoCashFuturesFeasibilityStore.class);
        var noAccount = byId(cashService(store, true, true, "",
            matchedCashQuotes()).evaluateAll(NOW)).get("strategy.STR-175");
        assertEquals("BLOCKED_DATA", noAccount.status());
        assertEquals("CASH_FUT_ACCOUNT_OR_STOCK_SCOPE_NOT_CONFIGURED", noAccount.reason());
        verifyNoInteractions(store);
        var noEvidence = byId(cashService(store, true, true, TEST_SCOPE,
            matchedCashQuotes()).evaluateAll(NOW)).get("strategy.STR-175");
        assertEquals("BLOCKED_DATA", noEvidence.status());
        assertEquals("CASH_FUT_DEPTH_MARGIN_DELIVERY_COST_EVIDENCE_MISSING", noEvidence.reason());
    }

    @Test
    void str175RejectsStalePriceDepthPriceMismatchAndWrongProvider() {
        var store = mock(FnoCashFuturesFeasibilityStore.class);
        var p = cashProof();
        when(store.currentSingleProof(any(), any(), any(), any(), anyInt(), any()))
            .thenReturn(Optional.of(new FnoCashFuturesFeasibilityStore.Proof(
                p.provider(), p.accountScope(), p.spotKey(), p.futureKey(), p.expiry(),
                p.lotSize(), p.spotAskDepthUnits(), p.futuresBidDepthUnits(),
                new BigDecimal("100.11"), p.futuresBidAtDepth(),
                p.depthExchangeAt(), p.depthFirstAvailableAt(), p.basis(), p.capital(), p.costs())));
        var mismatch = byId(cashService(store, true, true, TEST_SCOPE,
            matchedCashQuotes()).evaluateAll(NOW)).get("strategy.STR-175");
        assertEquals("BLOCKED_DATA", mismatch.status());
        assertEquals("CASH_FUT_PRICE_DEPTH_OR_ACCOUNT_PROOF_MISMATCH", mismatch.reason());
        when(store.currentSingleProof(any(), any(), any(), any(), anyInt(), any()))
            .thenReturn(Optional.of(new FnoCashFuturesFeasibilityStore.Proof(
                "ANGEL_ONE_SMARTAPI", p.accountScope(), p.spotKey(), p.futureKey(),
                p.expiry(), p.lotSize(), p.spotAskDepthUnits(), p.futuresBidDepthUnits(),
                p.spotAskAtDepth(), p.futuresBidAtDepth(),
                p.depthExchangeAt(), p.depthFirstAvailableAt(), p.basis(), p.capital(), p.costs())));
        assertEquals("CASH_FUT_PRICE_DEPTH_OR_ACCOUNT_PROOF_MISMATCH",
            byId(cashService(store, true, true, TEST_SCOPE, matchedCashQuotes())
                .evaluateAll(NOW)).get("strategy.STR-175").reason());
    }

    @Test
    void str175DepthBelowFullLotIsInfeasibleNotACashAndCarrySignal() {
        var store = mock(FnoCashFuturesFeasibilityStore.class);
        var p = cashProof();
        when(store.currentSingleProof(any(), any(), any(), any(), anyInt(), any()))
            .thenReturn(Optional.of(new FnoCashFuturesFeasibilityStore.Proof(
                p.provider(), p.accountScope(), p.spotKey(), p.futureKey(),
                p.expiry(), p.lotSize(), 249L, 500L,
                p.spotAskAtDepth(), p.futuresBidAtDepth(),
                p.depthExchangeAt(), p.depthFirstAvailableAt(),
                p.basis(), p.capital(), p.costs())));
        var result = byId(cashService(store, true, true, TEST_SCOPE,
            matchedCashQuotes()).evaluateAll(NOW)).get("strategy.STR-175");
        assertEquals("INFEASIBLE", result.status());
        assertEquals("CASH_FUT_TWO_LEG_DEPTH_INSUFFICIENT", result.reason());
    }

    @Test
    void str175NoLiveSpotQuoteNeverRequestsMarginEvidenceOrInventsDepth() {
        var store = mock(FnoCashFuturesFeasibilityStore.class);
        var live = liveWith(Map.of(CASH_NEAR.instrumentKey(),
            quote(CASH_NEAR.instrumentKey(), "103.50", "103.70")));
        var result = byId(cashService(store, true, true, TEST_SCOPE, live)
            .evaluateAll(NOW)).get("strategy.STR-175");
        assertEquals("BLOCKED_DATA", result.status());
        assertEquals("CASH_FUT_MATCHED_LIVE_QUOTES_MISSING", result.reason());
        verifyNoInteractions(store);
    }

    @Test
    void str175SessionClosedAndStoreExceptionsFailClosedWithoutSensitiveText() {
        var store = mock(FnoCashFuturesFeasibilityStore.class);
        assertEquals("CASH_FUT_EXCHANGE_SESSION_CLOSED",
            byId(cashService(store, true, false, TEST_SCOPE, matchedCashQuotes())
                .evaluateAll(NOW)).get("strategy.STR-175").reason());
        verifyNoInteractions(store);
        when(store.currentSingleProof(any(), any(), any(), any(), anyInt(), any()))
            .thenThrow(new IllegalStateException("private account and token"));
        var row = byId(cashService(store, true, true, TEST_SCOPE, matchedCashQuotes())
            .evaluateAll(NOW)).get("strategy.STR-175");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("CASH_FUT_FEASIBILITY_EVIDENCE_READ_UNAVAILABLE", row.reason());
        assertFalse(row.reason().contains("private"));
    }


    private static List<FnoVerifiedCompletedOhlcvOiStore.VerifiedBar> momentumBars() {
        int[] days = {12, 13, 16, 17, 18, 19, 20};
        String[] closes = {"100", "101", "102", "103", "104", "105", "111"};
        var list = new java.util.ArrayList<FnoVerifiedCompletedOhlcvOiStore.VerifiedBar>();
        for (int i = 0; i < days.length; i++) {
            LocalDate day = LocalDate.of(2026, 11, days[i]);
            Instant closeAt = day.atTime(15, 40)
                .atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
            BigDecimal p = new BigDecimal(closes[i]);
            list.add(new FnoVerifiedCompletedOhlcvOiStore.VerifiedBar(
                "UPSTOX_ANALYTICS", NEAR.instrumentKey(), NEAR.underlyingKey(),
                LocalDate.of(2026, 11, 1), NEAR.expiry(), NEAR.lotSize(),
                day, p.subtract(BigDecimal.ONE), p.add(new BigDecimal("0.5")),
                p.subtract(new BigDecimal("1.5")), p,
                i < 4 ? 30000L : 50000L, 100000L,
                closeAt.plusSeconds(120), closeAt.plusSeconds(900), closeAt));
        }
        return list;
    }

    private static FnoFuturesResearchService withMomentumStore(
            FnoVerifiedCompletedOhlcvOiStore completed, boolean rights) {
        return new FnoFuturesResearchService(feed(null), validCalendar(),
            currentRegistry(), emptyStore(), null, null, completed,
            true, true, false, false, rights, "", "");
    }

    @Test
    void str176RunsDistinctSevenSessionMomentumEvaluatorButCannotPlaceOrders() {
        var completed = mock(FnoVerifiedCompletedOhlcvOiStore.class);
        when(completed.readExactContract(eq(NEAR.instrumentKey()),
            eq(TODAY.minusDays(21)), eq(TODAY), eq(NOW)))
            .thenReturn(momentumBars());
        var actual = byId(withMomentumStore(completed, true).evaluateAll(NOW))
            .get("strategy.STR-176");
        assertNotNull(actual);
        assertEquals("RESEARCH_CANDIDATE", actual.status());
        assertEquals("FUT_MOM_UPWARD_BREAKOUT_OBSERVED", actual.reason());
        assertEquals(1, actual.contracts());
        assertEquals(0, actual.quotes());
        assertEquals(7, actual.history());
        assertEquals("COMPLETED_OHLCV_OI_RESEARCH_NO_ORDER", actual.input());
        verify(completed).readExactContract(eq(NEAR.instrumentKey()),
            eq(TODAY.minusDays(21)), eq(TODAY), eq(NOW));
    }

    @Test
    void str176DefaultRightsDeniedNeverReadsSevenSessionSourceOrMaster() {
        var completed = mock(FnoVerifiedCompletedOhlcvOiStore.class);
        var registry = currentRegistry();
        var service = new FnoFuturesResearchService(feed(null), validCalendar(),
            registry, emptyStore(), null, null, completed,
            true, true, false, false, false, "", "");
        var row = byId(service.evaluateAll(NOW)).get("strategy.STR-176");
        assertEquals("BLOCKED_RIGHTS", row.status());
        assertEquals("FUT_MOM_SOURCE_RIGHTS_NOT_VERIFIED", row.reason());
        verifyNoInteractions(completed);
    }

    @Test
    void str176MissingRealFinalizedOhlcvOiRowsRemainBlocked() {
        var completed = mock(FnoVerifiedCompletedOhlcvOiStore.class);
        var row = byId(withMomentumStore(completed, true).evaluateAll(NOW))
            .get("strategy.STR-176");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("FUT_MOM_SEVEN_VERIFIED_SESSIONS_MISSING", row.reason());
        assertEquals(0, row.history());
    }

    @Test
    void str176RejectsMixedProvidersDuplicateDatesAndChangedExpiryWithoutSubstitution() {
        var completed = mock(FnoVerifiedCompletedOhlcvOiStore.class);
        var original = momentumBars();
        var mixed = new java.util.ArrayList<>(original);
        var end = mixed.get(6);
        mixed.set(6, new FnoVerifiedCompletedOhlcvOiStore.VerifiedBar(
            "ANGEL_ONE_SMARTAPI", end.instrumentKey(), end.underlyingKey(),
            end.listedFrom(), end.expiry(), end.lotSize(), end.sessionDate(),
            end.open(), end.high(), end.low(), end.close(), end.tradedVolumeUnits(),
            end.openInterestUnits(), end.ohlcvFirstAvailableAt(),
            end.oiFirstAvailableAt(), end.verifiedSessionCloseAt()));
        when(completed.readExactContract(any(), any(), any(), any()))
            .thenReturn(mixed);
        assertEquals("FUT_MOM_SOURCE_ROWS_INCOMPLETE_OR_AMBIGUOUS",
            byId(withMomentumStore(completed, true).evaluateAll(NOW))
                .get("strategy.STR-176").reason());
        var duplicate = new java.util.ArrayList<>(original);
        duplicate.set(6, duplicate.get(5));
        when(completed.readExactContract(any(), any(), any(), any()))
            .thenReturn(duplicate);
        assertEquals("FUT_MOM_SOURCE_ROWS_INCOMPLETE_OR_AMBIGUOUS",
            byId(withMomentumStore(completed, true).evaluateAll(NOW))
                .get("strategy.STR-176").reason());
    }

    @Test
    void str176MissingTradingDateAndPreClosePublicationFailClosed() {
        var completed = mock(FnoVerifiedCompletedOhlcvOiStore.class);
        var rows = new java.util.ArrayList<>(momentumBars());
        rows.remove(3);
        when(completed.readExactContract(any(), any(), any(), any()))
            .thenReturn(rows);
        assertEquals("FUT_MOM_SEVEN_VERIFIED_SESSIONS_MISSING",
            byId(withMomentumStore(completed, true).evaluateAll(NOW))
                .get("strategy.STR-176").reason());
        rows = new java.util.ArrayList<>(momentumBars());
        var last = rows.get(6);
        rows.set(6, new FnoVerifiedCompletedOhlcvOiStore.VerifiedBar(
            last.provider(), last.instrumentKey(), last.underlyingKey(),
            last.listedFrom(), last.expiry(), last.lotSize(), last.sessionDate(),
            last.open(), last.high(), last.low(), last.close(),
            last.tradedVolumeUnits(), last.openInterestUnits(),
            last.verifiedSessionCloseAt().minusSeconds(60),
            last.oiFirstAvailableAt(), last.verifiedSessionCloseAt()));
        when(completed.readExactContract(any(), any(), any(), any()))
            .thenReturn(rows);
        assertEquals("FUT_MOM_COMPLETED_BAR_INVALID",
            byId(withMomentumStore(completed, true).evaluateAll(NOW))
                .get("strategy.STR-176").reason());
    }

    @Test
    void str176SourceExceptionNeverLeaksProviderOrTokenDetails() {
        var completed = mock(FnoVerifiedCompletedOhlcvOiStore.class);
        when(completed.readExactContract(any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("private bearer token"));
        var row = byId(withMomentumStore(completed, true).evaluateAll(NOW))
            .get("strategy.STR-176");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("FUT_MOM_SOURCE_READ_UNAVAILABLE", row.reason());
        assertFalse(row.reason().contains("bearer"));
    }


    private static List<FnoVerifiedCompletedOhlcvOiStore.VerifiedBar> meanReversionBars() {
        int[] days = {9, 10, 11, 12, 13, 16, 17, 18, 19, 20};
        String[] closes = {"100", "101", "99", "100", "100.5", "99.5",
                           "101", "99", "95", "96.5"};
        var result = new java.util.ArrayList<FnoVerifiedCompletedOhlcvOiStore.VerifiedBar>();
        for (int i = 0; i < days.length; i++) {
            LocalDate day = LocalDate.of(2026, 11, days[i]);
            Instant sessionClose = day.atTime(15, 40)
                .atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
            BigDecimal price = new BigDecimal(closes[i]);
            result.add(new FnoVerifiedCompletedOhlcvOiStore.VerifiedBar(
                "UPSTOX_ANALYTICS", NEAR.instrumentKey(), NEAR.underlyingKey(),
                LocalDate.of(2026, 11, 1), NEAR.expiry(), NEAR.lotSize(),
                day, price, price.add(new BigDecimal("0.4")),
                price.subtract(new BigDecimal("0.4")), price,
                i < 8 ? 30000L : (i == 8 ? 45000L : 50000L), 100000L,
                sessionClose.plusSeconds(150), sessionClose.plusSeconds(900),
                sessionClose));
        }
        return result;
    }

    @Test
    void str177DirectlyEvaluatesTenSourceVerifiedSessionsAsReadOnlyResearch() {
        var source = mock(FnoVerifiedCompletedOhlcvOiStore.class);
        when(source.readExactContract(eq(NEAR.instrumentKey()),
            eq(TODAY.minusDays(21)), eq(TODAY), eq(NOW)))
            .thenReturn(meanReversionBars());
        var result = byId(withMomentumStore(source, true).evaluateAll(NOW))
            .get("strategy.STR-177");
        assertNotNull(result);
        assertEquals("RESEARCH_CANDIDATE", result.status());
        assertEquals("FUT_MR_LOW_EXTREME_REBOUND_OBSERVED", result.reason());
        assertEquals(1, result.contracts());
        assertEquals(0, result.quotes());
        assertEquals(10, result.history());
        assertEquals("COMPLETED_OHLCV_OI_MEAN_REVERSION_RESEARCH_NO_ORDER", result.input());
        // One exact as-of source read is fanned out to STR-176 and STR-177.
        verify(source, times(1)).readExactContract(eq(NEAR.instrumentKey()),
            eq(TODAY.minusDays(21)), eq(TODAY), eq(NOW));
    }

    @Test
    void str177DefaultDeniedSourceRightsDoNotReadHistoryOrInventABrokerOrder() {
        var source = mock(FnoVerifiedCompletedOhlcvOiStore.class);
        var result = byId(withMomentumStore(source, false).evaluateAll(NOW))
            .get("strategy.STR-177");
        assertEquals("BLOCKED_RIGHTS", result.status());
        assertEquals("FUT_MR_SOURCE_RIGHTS_NOT_VERIFIED", result.reason());
        verifyNoInteractions(source);
    }

    @Test
    void str177AbsentTenCompleteSessionsCannotBecomeReversionSignal() {
        var source = mock(FnoVerifiedCompletedOhlcvOiStore.class);
        when(source.readExactContract(any(), any(), any(), any()))
            .thenReturn(momentumBars());
        var result = byId(withMomentumStore(source, true).evaluateAll(NOW))
            .get("strategy.STR-177");
        assertEquals("BLOCKED_DATA", result.status());
        assertEquals("FUT_MR_TEN_VERIFIED_SESSIONS_MISSING", result.reason());
        assertEquals(7, result.history());
    }

    @Test
    void str177SameContractListingExpiryProviderAndSessionOrderAreMandatory() {
        var source = mock(FnoVerifiedCompletedOhlcvOiStore.class);
        var original = meanReversionBars();
        var mixed = new java.util.ArrayList<>(original);
        var last = mixed.get(9);
        mixed.set(9, new FnoVerifiedCompletedOhlcvOiStore.VerifiedBar(
            "ANGEL_ONE_SMARTAPI", last.instrumentKey(), last.underlyingKey(),
            last.listedFrom(), last.expiry(), last.lotSize(), last.sessionDate(),
            last.open(), last.high(), last.low(), last.close(), last.tradedVolumeUnits(),
            last.openInterestUnits(), last.ohlcvFirstAvailableAt(),
            last.oiFirstAvailableAt(), last.verifiedSessionCloseAt()));
        when(source.readExactContract(any(), any(), any(), any())).thenReturn(mixed);
        assertEquals("FUT_MR_SOURCE_ROWS_INCOMPLETE_OR_AMBIGUOUS",
            byId(withMomentumStore(source, true).evaluateAll(NOW))
                .get("strategy.STR-177").reason());
        var duplicate = new java.util.ArrayList<>(original);
        duplicate.set(9, duplicate.get(8));
        when(source.readExactContract(any(), any(), any(), any())).thenReturn(duplicate);
        assertEquals("FUT_MR_SOURCE_ROWS_INCOMPLETE_OR_AMBIGUOUS",
            byId(withMomentumStore(source, true).evaluateAll(NOW))
                .get("strategy.STR-177").reason());
    }

    @Test
    void str177MissingVerifiedTradingDateFailsRatherThanStitchingBars() {
        var source = mock(FnoVerifiedCompletedOhlcvOiStore.class);
        var original = meanReversionBars();
        // Drop Tuesday 17 and replace with earlier trading session to keep ten rows.
        var changed = new java.util.ArrayList<>(original);
        changed.set(6, new FnoVerifiedCompletedOhlcvOiStore.VerifiedBar(
            "UPSTOX_ANALYTICS", NEAR.instrumentKey(), NEAR.underlyingKey(),
            LocalDate.of(2026, 11, 1), NEAR.expiry(), NEAR.lotSize(),
            LocalDate.of(2026, 11, 14), new BigDecimal("101"),
            new BigDecimal("101.4"), new BigDecimal("100.6"), new BigDecimal("101"),
            30000L, 100000L, changed.get(6).ohlcvFirstAvailableAt(),
            changed.get(6).oiFirstAvailableAt(),
            changed.get(6).verifiedSessionCloseAt()));
        when(source.readExactContract(any(), any(), any(), any())).thenReturn(changed);
        var result = byId(withMomentumStore(source, true).evaluateAll(NOW))
            .get("strategy.STR-177");
        assertEquals("BLOCKED_DATA", result.status());
        assertEquals("FUT_MR_SOURCE_ROWS_INCOMPLETE_OR_AMBIGUOUS", result.reason());
    }

    @Test
    void str177ProviderFailureDoesNotRevealPrivateSourceDetails() {
        var source = mock(FnoVerifiedCompletedOhlcvOiStore.class);
        when(source.readExactContract(any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("secret account credential"));
        var result = byId(withMomentumStore(source, true).evaluateAll(NOW))
            .get("strategy.STR-177");
        assertEquals("BLOCKED_DATA", result.status());
        assertEquals("FUT_MR_SOURCE_READ_UNAVAILABLE", result.reason());
        assertFalse(result.reason().contains("secret"));
    }


    private static final String HEDGE_SCOPE = "personal_hedge_01";

    private static FnoIndexHedgeResearchStore.Proof hedgeProof() {
        return new FnoIndexHedgeResearchStore.Proof(
            "UPSTOX_V3", HEDGE_SCOPE, NIFTY, NEAR.instrumentKey(),
            NEAR.expiry(), NEAR.lotSize(),
            new FnoFuturesHedgeOverlayV1.Portfolio(
                NIFTY, new BigDecimal("3000000"),
                NOW.minusSeconds(120), NOW.minusSeconds(60),
                true, true, true),
            new FnoFuturesHedgeOverlayV1.MarketQuote(
                NEAR.instrumentKey(), "UPSTOX_V3",
                new BigDecimal("20000"), new BigDecimal("20004"),
                300L, 300L, 1000000L, NOW.minusSeconds(2),
                NOW.minusSeconds(1), true, true),
            new FnoFuturesHedgeOverlayV1.Funding(
                new BigDecimal("300000"), new BigDecimal("80000"),
                new BigDecimal("20000"), true, true),
            new FnoFuturesHedgeOverlayV1.Stress(
                500, new BigDecimal("1000"), new BigDecimal("500"),
                10, 50, 3000, true, true));
    }

    private static FnoFuturesResearchService hedgeService(FnoIndexHedgeResearchStore proofStore,
            boolean rights, boolean sessionOpen, String account,
            UpstoxFnoV3LiveFeedService feedSource) {
        return new FnoFuturesResearchService(feed(feedSource), calendar(sessionOpen),
            currentRegistry(), emptyStore(), null, null, null, proofStore,
            true, true, false, false, false, rights, account, "", "");
    }

    private static UpstoxFnoV3LiveFeedService hedgeFeed() {
        return liveWith(Map.of(NEAR.instrumentKey(),
            quote(NEAR.instrumentKey(), "20000", "20004")));
    }

    @Test
    void str178ReadsOneReconciledPortfolioBetaAndComputesReadOnlyShortIndexHedge() {
        var proofs = mock(FnoIndexHedgeResearchStore.class);
        when(proofs.oneCurrentProof(eq(HEDGE_SCOPE), eq(NIFTY), eq(NEAR.instrumentKey()),
            eq(NEAR.expiry()), eq(NEAR.lotSize()), eq(NOW)))
            .thenReturn(Optional.of(hedgeProof()));
        var row = byId(hedgeService(proofs, true, true, HEDGE_SCOPE, hedgeFeed())
            .evaluateAll(NOW)).get("strategy.STR-178");
        assertEquals("RESEARCH_CANDIDATE", row.status());
        assertEquals("HEDGE_STRESSED_EXPOSURE_REDUCTION_RESEARCH_ONLY", row.reason());
        assertEquals(1, row.contracts());
        assertEquals(1, row.quotes());
        assertEquals(0, row.history());
        assertEquals("RECONCILED_INDEX_HEDGE_RESEARCH_NO_ORDER", row.input());
        assertFalse(row.input().contains(HEDGE_SCOPE));
        verify(proofs).oneCurrentProof(eq(HEDGE_SCOPE), eq(NIFTY),
            eq(NEAR.instrumentKey()), eq(NEAR.expiry()), eq(NEAR.lotSize()), eq(NOW));
    }

    @Test
    void str178DeniedResearchRightsNeverReadsSensitivePortfolioEvidence() {
        var proofs = mock(FnoIndexHedgeResearchStore.class);
        var row = byId(hedgeService(proofs, false, true, HEDGE_SCOPE, hedgeFeed())
            .evaluateAll(NOW)).get("strategy.STR-178");
        assertEquals("BLOCKED_RIGHTS", row.status());
        assertEquals("HEDGE_RESEARCH_RIGHTS_NOT_VERIFIED", row.reason());
        verifyNoInteractions(proofs);
    }

    @Test
    void str178MissingAccountBalanceOrSourceProofBlocksInsteadOfAssumingFunds() {
        var proofs = mock(FnoIndexHedgeResearchStore.class);
        var missingScope = byId(hedgeService(proofs, true, true, "", hedgeFeed())
            .evaluateAll(NOW)).get("strategy.STR-178");
        assertEquals("HEDGE_ACCOUNT_OR_SOURCE_NOT_CONFIGURED", missingScope.reason());
        verifyNoInteractions(proofs);
        var missingReceipts = byId(hedgeService(proofs, true, true, HEDGE_SCOPE, hedgeFeed())
            .evaluateAll(NOW)).get("strategy.STR-178");
        assertEquals("BLOCKED_DATA", missingReceipts.status());
        assertEquals("HEDGE_BETA_MARGIN_DEPTH_RISK_PROOF_MISSING", missingReceipts.reason());
    }

    @Test
    void str178RejectsUnmatchedQuoteProviderAndMarketIdentity() {
        var proofs = mock(FnoIndexHedgeResearchStore.class);
        var original = hedgeProof();
        var mismatch = new FnoIndexHedgeResearchStore.Proof(
            original.provider(), original.accountScope(), original.indexKey(),
            original.futureKey(), original.expiry(), original.lotSize(),
            original.portfolio(),
            new FnoFuturesHedgeOverlayV1.MarketQuote(
                original.futureKey(), "UPSTOX_V3", new BigDecimal("19999"),
                new BigDecimal("20004"), 300, 300, 1000000,
                NOW.minusSeconds(2), NOW.minusSeconds(1), true, true),
            original.funding(), original.stress());
        when(proofs.oneCurrentProof(any(), any(), any(), any(), anyInt(), any()))
            .thenReturn(Optional.of(mismatch));
        var row = byId(hedgeService(proofs, true, true, HEDGE_SCOPE, hedgeFeed())
            .evaluateAll(NOW)).get("strategy.STR-178");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("HEDGE_QUOTE_ACCOUNT_OR_RISK_PROOF_MISMATCH", row.reason());
    }

    @Test
    void str178RequiresFreshIndexFutureAndOpenDerivativesSession() {
        var proofs = mock(FnoIndexHedgeResearchStore.class);
        var closed = byId(hedgeService(proofs, true, false, HEDGE_SCOPE, hedgeFeed())
            .evaluateAll(NOW)).get("strategy.STR-178");
        assertEquals("HEDGE_FUTURES_SESSION_CLOSED", closed.reason());
        var missingQuote = byId(hedgeService(proofs, true, true, HEDGE_SCOPE,
            liveWith(Map.of())).evaluateAll(NOW)).get("strategy.STR-178");
        assertEquals("HEDGE_LIVE_INDEX_FUTURE_QUOTE_MISSING", missingQuote.reason());
        verifyNoInteractions(proofs);
    }

    @Test
    void str178PrivateProviderExceptionIsSanitizedAndNeverPromotesRiskReadiness() {
        var proofs = mock(FnoIndexHedgeResearchStore.class);
        when(proofs.oneCurrentProof(any(), any(), any(), any(), anyInt(), any()))
            .thenThrow(new IllegalStateException("secret portfolio broker token"));
        var row = byId(hedgeService(proofs, true, true, HEDGE_SCOPE, hedgeFeed())
            .evaluateAll(NOW)).get("strategy.STR-178");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("HEDGE_PORTFOLIO_EVIDENCE_READ_UNAVAILABLE", row.reason());
        assertFalse(row.reason().contains("secret"));
    }


    private static List<FnoVerifiedIntradayLiquidityStore.Snapshot> threeLiquiditySnapshots() {
        int[] delays = {35, 20, 5};
        String[] bid = {"20000", "20001", "20002"};
        String[] ask = {"20004", "20005", "20006"};
        var result = new java.util.ArrayList<FnoVerifiedIntradayLiquidityStore.Snapshot>();
        for (int i = 0; i < delays.length; i++) {
            int ago = delays[i];
            result.add(new FnoVerifiedIntradayLiquidityStore.Snapshot(
                "UPSTOX_V3", NEAR.instrumentKey(), NEAR.underlyingKey(),
                NEAR.expiry(), NEAR.lotSize(), "FUTURE",
                new BigDecimal(bid[i]), new BigDecimal(ask[i]),
                225L, 250L, 20000L, 5000L + i * 50L,
                NOW.minusSeconds(ago), NOW.minusSeconds(ago - 1),
                NOW.minusSeconds(ago + 35), NOW.minusSeconds(ago - 1)));
        }
        return result;
    }

    private static FnoFuturesResearchService liquidityService(
            FnoVerifiedIntradayLiquidityStore source, boolean rights,
            boolean sessionOpen, UpstoxFnoV3LiveFeedService live) {
        return new FnoFuturesResearchService(
            feed(live), calendar(sessionOpen), currentRegistry(), emptyStore(),
            null, null, null, null, source,
            true, true, false, false, false, false, rights,
            "", "", "");
    }

    private static UpstoxFnoV3LiveFeedService liquidityFeed() {
        return liveWith(Map.of(NEAR.instrumentKey(),
            quote(NEAR.instrumentKey(), "20002", "20006")));
    }

    @Test
    void str179ThreeSourceVerifiedLiquiditySnapshotsPassResearchNotBrokerExecution() {
        var source = mock(FnoVerifiedIntradayLiquidityStore.class);
        when(source.latestThreeFutures(eq(NEAR.instrumentKey()),
            eq(NEAR.expiry()), eq(NEAR.lotSize()), eq(NOW)))
            .thenReturn(threeLiquiditySnapshots());
        var row = byId(liquidityService(source, true, true, liquidityFeed())
            .evaluateAll(NOW)).get("strategy.STR-179");
        assertEquals("RESEARCH_PASS", row.status());
        assertEquals("LIQUIDITY_RESEARCH_SCREEN_PASSED", row.reason());
        assertEquals(1, row.contracts());
        assertEquals(1, row.quotes());
        assertEquals(3, row.history());
        assertEquals("THREE_SOURCE_OBSERVED_LIQUIDITY_SNAPSHOTS_NO_ORDER", row.input());
        verify(source).latestThreeFutures(eq(NEAR.instrumentKey()),
            eq(NEAR.expiry()), eq(NEAR.lotSize()), eq(NOW));
    }

    @Test
    void str179RightsDeniedNeverReadsMarketDepthOiOrVolume() {
        var source = mock(FnoVerifiedIntradayLiquidityStore.class);
        var row = byId(liquidityService(source, false, true, liquidityFeed())
            .evaluateAll(NOW)).get("strategy.STR-179");
        assertEquals("BLOCKED_RIGHTS", row.status());
        assertEquals("LIQUIDITY_SOURCE_RIGHTS_NOT_VERIFIED", row.reason());
        verifyNoInteractions(source);
    }

    @Test
    void str179TwoOrFewerSourceSnapshotsCannotApproveLiquidity() {
        var source = mock(FnoVerifiedIntradayLiquidityStore.class);
        when(source.latestThreeFutures(any(), any(), anyInt(), any()))
            .thenReturn(threeLiquiditySnapshots().subList(0, 2));
        var row = byId(liquidityService(source, true, true, liquidityFeed())
            .evaluateAll(NOW)).get("strategy.STR-179");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("LIQUIDITY_THREE_SOURCE_SNAPSHOTS_MISSING", row.reason());
        assertEquals(2, row.history());
    }

    @Test
    void str179ProviderExpiryOrMetadataMismatchesStayBlocked() {
        var source = mock(FnoVerifiedIntradayLiquidityStore.class);
        var rows = new java.util.ArrayList<>(threeLiquiditySnapshots());
        var last = rows.get(2);
        rows.set(2, new FnoVerifiedIntradayLiquidityStore.Snapshot(
            "ANGEL_ONE_SMARTAPI", last.instrumentKey(), last.underlyingKey(),
            last.expiry(), last.lotSize(), last.kind(), last.bid(), last.ask(),
            last.bidDepthUnits(), last.askDepthUnits(), last.oiUnits(),
            last.cumulativeVolumeUnits(), last.exchangeAt(), last.observedAt(),
            last.oiFirstObservedAt(), last.volumeFirstObservedAt()));
        when(source.latestThreeFutures(any(), any(), anyInt(), any()))
            .thenReturn(rows);
        var row = byId(liquidityService(source, true, true, liquidityFeed())
            .evaluateAll(NOW)).get("strategy.STR-179");
        assertEquals("LIQUIDITY_SOURCE_ROWS_INCOMPLETE_OR_AMBIGUOUS", row.reason());
    }

    @Test
    void str179UnmatchedLastLiveQuoteNeverConfirmsBookOrOpenInterest() {
        var source = mock(FnoVerifiedIntradayLiquidityStore.class);
        when(source.latestThreeFutures(any(), any(), anyInt(), any()))
            .thenReturn(threeLiquiditySnapshots());
        var wrongFeed = liveWith(Map.of(NEAR.instrumentKey(),
            quote(NEAR.instrumentKey(), "20100", "20104")));
        var row = byId(liquidityService(source, true, true, wrongFeed)
            .evaluateAll(NOW)).get("strategy.STR-179");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("LIQUIDITY_SOURCE_LIVE_QUOTE_MISMATCH", row.reason());
    }

    @Test
    void str179MissingLiveQuoteOrClosedSessionSkipsSourceRead() {
        var source = mock(FnoVerifiedIntradayLiquidityStore.class);
        assertEquals("LIQUIDITY_FNO_SESSION_CLOSED",
            byId(liquidityService(source, true, false, liquidityFeed())
                .evaluateAll(NOW)).get("strategy.STR-179").reason());
        assertEquals("LIQUIDITY_CURRENT_LIVE_QUOTE_MISSING",
            byId(liquidityService(source, true, true, liveWith(Map.of()))
                .evaluateAll(NOW)).get("strategy.STR-179").reason());
        verifyNoInteractions(source);
    }

    @Test
    void str179SourceFailureNeverLeaksPrivateFeedOrAccountMessage() {
        var source = mock(FnoVerifiedIntradayLiquidityStore.class);
        when(source.latestThreeFutures(any(), any(), anyInt(), any()))
            .thenThrow(new IllegalStateException("secret private provider token"));
        var row = byId(liquidityService(source, true, true, liquidityFeed())
            .evaluateAll(NOW)).get("strategy.STR-179");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("LIQUIDITY_SOURCE_READ_UNAVAILABLE", row.reason());
        assertFalse(row.reason().contains("secret"));
    }


    private static final String EXPIRY_BASIS_SPOT = "NSE_EQ|INE002A01018";
    private static final FuturesContract EXPIRY_STOCK_NEAR = new FuturesContract(
        "NSE_FO|STOCK_NOV26", EXPIRY_BASIS_SPOT, "TEST_STOCK",
        "TEST STOCK FUT NOV26", LocalDate.of(2026, 11, 27), 250);
    private static final FuturesContract EXPIRY_STOCK_FAR = new FuturesContract(
        "NSE_FO|STOCK_DEC26", EXPIRY_BASIS_SPOT, "TEST_STOCK",
        "TEST STOCK FUT DEC26", LocalDate.of(2026, 12, 29), 250);

    private static FnoFuturesContractRegistry expiryStockRegistry() {
        var r = currentRegistry();
        when(r.nearFar(eq(EXPIRY_BASIS_SPOT), any()))
            .thenReturn(Optional.of(new NearFar(EXPIRY_STOCK_NEAR, EXPIRY_STOCK_FAR)));
        return r;
    }

    private static FnoExpiryBasisReversionV1.Assumptions expiryBasisAssumptions() {
        return new FnoExpiryBasisReversionV1.Assumptions(
            new BigDecimal("500"), BigDecimal.ZERO, new BigDecimal("20"),
            3, 150, 30, 50, 2500, true, true, true, true);
    }

    private static List<FnoVerifiedExpiryBasisPairStore.PairProof> expiryBasisPairs() {
        int[] days = {16, 17, 18, 19, 20};
        String[] futuresMids = {"104", "103.2", "102.6", "101.8", "100.85"};
        var pairs = new java.util.ArrayList<FnoVerifiedExpiryBasisPairStore.PairProof>();
        for (int i = 0; i < days.length; i++) {
            LocalDate date = LocalDate.of(2026, 11, days[i]);
            Instant cashClose = FnoExpiryBasisReversionV1.cashClose(date);
            Instant futureClose = FnoExpiryBasisReversionV1.futuresClose(date);
            BigDecimal v = new BigDecimal(futuresMids[i]);
            pairs.add(new FnoVerifiedExpiryBasisPairStore.PairProof(
                "UPSTOX_ANALYTICS", EXPIRY_BASIS_SPOT, EXPIRY_STOCK_NEAR.instrumentKey(),
                LocalDate.of(2026, 11, 1), EXPIRY_STOCK_NEAR.expiry(),
                EXPIRY_STOCK_NEAR.lotSize(), date,
                new BigDecimal("99.99"), new BigDecimal("100.01"),
                v.subtract(new BigDecimal("0.01")), v.add(new BigDecimal("0.01")),
                cashClose.minusSeconds(45), cashClose.minusSeconds(30),
                cashClose.plusSeconds(30), cashClose.plusSeconds(30),
                cashClose.plusSeconds(60), futureClose.plusSeconds(80),
                cashClose, futureClose, true,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                expiryBasisAssumptions()));
        }
        return pairs;
    }

    private static FnoFuturesResearchService withExpiryBasis(
            FnoVerifiedExpiryBasisPairStore pairs, boolean granted) {
        return withExpiryBasis(pairs, granted, EXPIRY_BASIS_SPOT);
    }

    private static FnoFuturesResearchService withExpiryBasis(
            FnoVerifiedExpiryBasisPairStore pairs, boolean granted, String spot) {
        return new FnoFuturesResearchService(
            feed(null), validCalendar(), expiryStockRegistry(), emptyStore(),
            null, null, null, null, null, pairs,
            true, true, false, false, false, false, false, granted,
            "", "", spot);
    }

    @Test
    void str180VerifiedFiveNearCashClosePairsEvaluateDistinctExpiryBasisReversionResearch() {
        var source = mock(FnoVerifiedExpiryBasisPairStore.class);
        when(source.readExactPair(eq(EXPIRY_BASIS_SPOT),
            eq(EXPIRY_STOCK_NEAR.instrumentKey()), eq(EXPIRY_STOCK_NEAR.expiry()),
            eq(250), eq(TODAY.minusDays(21)), eq(TODAY), eq(NOW)))
            .thenReturn(expiryBasisPairs());
        var actual = byId(withExpiryBasis(source, true).evaluateAll(NOW))
            .get("strategy.STR-180");
        assertNotNull(actual);
        assertEquals("RESEARCH_CANDIDATE", actual.status());
        assertEquals("EXPIRY_BASIS_ABOVE_FAIR_CONVERGENCE_OBSERVED", actual.reason());
        assertEquals(1, actual.contracts());
        assertEquals(0, actual.quotes());
        assertEquals(5, actual.history());
        assertEquals("VERIFIED_EXPIRY_BASIS_CASH_CLOSE_PAIRS_RESEARCH_NO_ORDER", actual.input());
        verify(source).readExactPair(eq(EXPIRY_BASIS_SPOT),
            eq(EXPIRY_STOCK_NEAR.instrumentKey()), eq(EXPIRY_STOCK_NEAR.expiry()),
            eq(250), eq(TODAY.minusDays(21)), eq(TODAY), eq(NOW));
    }

    @Test
    void str180DefaultDeniedResearchRightsNeverAccessPairedQuoteEvidence() {
        var source = mock(FnoVerifiedExpiryBasisPairStore.class);
        var row = byId(withExpiryBasis(source, false).evaluateAll(NOW))
            .get("strategy.STR-180");
        assertEquals("BLOCKED_RIGHTS", row.status());
        assertEquals("EXPIRY_BASIS_SOURCE_RIGHTS_MISSING", row.reason());
        verifyNoInteractions(source);
    }

    @Test
    void str180IndexIsNotDeliverableSpotAndUnconfiguredEquityCannotPass() {
        var source = mock(FnoVerifiedExpiryBasisPairStore.class);
        var row = byId(withExpiryBasis(source, true, NIFTY).evaluateAll(NOW))
            .get("strategy.STR-180");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("EXPIRY_BASIS_SPOT_CONTRACT_NOT_CONFIGURED", row.reason());
        verifyNoInteractions(source);
    }

    @Test
    void str180NoFiveFullVerifiedSessionsCannotBeConvertedIntoArbitrage() {
        var source = mock(FnoVerifiedExpiryBasisPairStore.class);
        when(source.readExactPair(any(), any(), any(), anyInt(), any(), any(), any()))
            .thenReturn(expiryBasisPairs().subList(0, 4));
        var row = byId(withExpiryBasis(source, true).evaluateAll(NOW))
            .get("strategy.STR-180");
        assertEquals("BLOCKED_DATA", row.status());
        assertEquals("EXPIRY_BASIS_FIVE_VERIFIED_SESSION_PAIRS_MISSING", row.reason());
        assertEquals(4, row.history());
    }

    @Test
    void str180RejectsProviderChangesContractChangesAndCarryAssumptionDrift() {
        var source = mock(FnoVerifiedExpiryBasisPairStore.class);
        var rows = new java.util.ArrayList<>(expiryBasisPairs());
        var old = rows.get(4);
        rows.set(4, new FnoVerifiedExpiryBasisPairStore.PairProof(
            "ANGEL_ONE_SMARTAPI", old.spotKey(), old.futuresKey(),
            old.listedFrom(), old.expiry(), old.lotSize(), old.sessionDate(),
            old.spotBid(), old.spotAsk(), old.futuresBid(), old.futuresAsk(),
            old.spotQuoteAt(), old.futuresQuoteAt(),
            old.spotQuoteFirstObservedAt(), old.futuresQuoteFirstObservedAt(),
            old.spotFinalFirstAvailableAt(), old.futuresFinalFirstAvailableAt(),
            old.cashSessionCloseAt(), old.futuresSessionCloseAt(),
            old.deliverableSpotVerified(), old.contractSourceDigest(),
            old.carryCostSourceDigest(), old.assumptions()));
        when(source.readExactPair(any(), any(), any(), anyInt(), any(), any(), any()))
            .thenReturn(rows);
        assertEquals("EXPIRY_BASIS_SOURCE_ROWS_INCOMPLETE_OR_AMBIGUOUS",
            byId(withExpiryBasis(source, true).evaluateAll(NOW))
                .get("strategy.STR-180").reason());
        rows.set(4, new FnoVerifiedExpiryBasisPairStore.PairProof(
            old.provider(), old.spotKey(), old.futuresKey(),
            old.listedFrom(), old.expiry(), old.lotSize(), old.sessionDate(),
            old.spotBid(), old.spotAsk(), old.futuresBid(), old.futuresAsk(),
            old.spotQuoteAt(), old.futuresQuoteAt(),
            old.spotQuoteFirstObservedAt(), old.futuresQuoteFirstObservedAt(),
            old.spotFinalFirstAvailableAt(), old.futuresFinalFirstAvailableAt(),
            old.cashSessionCloseAt(), old.futuresSessionCloseAt(),
            old.deliverableSpotVerified(), old.contractSourceDigest(),
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            old.assumptions()));
        assertEquals("EXPIRY_BASIS_SOURCE_ROWS_INCOMPLETE_OR_AMBIGUOUS",
            byId(withExpiryBasis(source, true).evaluateAll(NOW))
                .get("strategy.STR-180").reason());
    }

    @Test
    void str180PrematureFuturesCloseSourceCannotPresentCompletedSession() {
        var source = mock(FnoVerifiedExpiryBasisPairStore.class);
        var rows = new java.util.ArrayList<>(expiryBasisPairs());
        var old = rows.get(4);
        rows.set(4, new FnoVerifiedExpiryBasisPairStore.PairProof(
            old.provider(), old.spotKey(), old.futuresKey(),
            old.listedFrom(), old.expiry(), old.lotSize(), old.sessionDate(),
            old.spotBid(), old.spotAsk(), old.futuresBid(), old.futuresAsk(),
            old.spotQuoteAt(), old.futuresQuoteAt(),
            old.spotQuoteFirstObservedAt(), old.futuresQuoteFirstObservedAt(),
            old.spotFinalFirstAvailableAt(),
            old.cashSessionCloseAt().plusSeconds(60),
            old.cashSessionCloseAt(), old.futuresSessionCloseAt(),
            old.deliverableSpotVerified(), old.contractSourceDigest(),
            old.carryCostSourceDigest(), old.assumptions()));
        when(source.readExactPair(any(), any(), any(), anyInt(), any(), any(), any()))
            .thenReturn(rows);
        var result = byId(withExpiryBasis(source, true).evaluateAll(NOW))
            .get("strategy.STR-180");
        assertEquals("BLOCKED_DATA", result.status());
        assertEquals("EXPIRY_BASIS_FINAL_PAIR_INVALID", result.reason());
    }

    @Test
    void str180ProviderExceptionNeverLeaksInstrumentRightsOrSecrets() {
        var source = mock(FnoVerifiedExpiryBasisPairStore.class);
        when(source.readExactPair(any(), any(), any(), anyInt(), any(), any(), any()))
            .thenThrow(new IllegalStateException("secret proprietary broker response"));
        var result = byId(withExpiryBasis(source, true).evaluateAll(NOW))
            .get("strategy.STR-180");
        assertEquals("BLOCKED_DATA", result.status());
        assertEquals("EXPIRY_BASIS_SOURCE_READ_UNAVAILABLE", result.reason());
        assertFalse(result.reason().contains("secret"));
    }

}
