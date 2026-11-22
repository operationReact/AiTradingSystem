package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyDebitSpreadSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FnoDebitSpreadResearchServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String PROVIDER = "UPSTOX_ANALYTICS";
    private static final String INDEX = "NSE_INDEX|Nifty 50";
    private static final String BULL_LOW = "NSE_FO|NIFTY_20261127_125_CE";
    private static final String BULL_HIGH = "NSE_FO|NIFTY_20261127_130_CE";
    private static final String BEAR_LOW = "NSE_FO|NIFTY_20261127_70_PE";
    private static final String BEAR_HIGH = "NSE_FO|NIFTY_20261127_75_PE";

    private static MarketSessionCalendar calendar() {
        var cal = mock(MarketSessionCalendar.class);
        when(cal.isInsideCashSession(NOW)).thenReturn(true);
        when(cal.isTradingDay(any(LocalDate.class))).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            return d.getDayOfWeek().getValue() <= 5;
        });
        return cal;
    }

    private static List<FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose>
            closes(String end) {
        var sessions = new ArrayList<LocalDate>();
        LocalDate date = LocalDate.of(2026,11,19);
        while (sessions.size() < 35) {
            if (date.getDayOfWeek().getValue() <= 5) sessions.add(0,date);
            date = date.minusDays(1);
        }
        var out = new ArrayList<FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose>();
        for (int i = 0; i < sessions.size(); i++) {
            var close = sessions.get(i).atTime(15,30).atZone(IST).toInstant();
            out.add(new FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose(
                PROVIDER, INDEX, sessions.get(i),
                new BigDecimal(i == 34 ? end : "100"), close.plusSeconds(60),
                close));
        }
        return out;
    }

    private static FnoVerifiedNiftyDebitSpreadSourceStore.Leg leg(
            String key, String kind, String strike, String bid, String ask) {
        return new FnoVerifiedNiftyDebitSpreadSourceStore.Leg(
            key, kind, LocalDate.of(2026,11,1), LocalDate.of(2026,11,27),
            50, new BigDecimal(strike), new BigDecimal("0.05"),
            new BigDecimal(bid), new BigDecimal(ask), 10, 10, 1000,
            NOW.minusSeconds(40), NOW.minusSeconds(30));
    }

    private static FnoVerifiedNiftyDebitSpreadSourceStore.Proof proof(boolean bull) {
        var lower = bull
            ? leg(BULL_LOW, "CE", "125", "5", "5.10")
            : leg(BEAR_LOW, "PE", "70", "2.50", "2.55");
        var higher = bull
            ? leg(BULL_HIGH, "CE", "130", "2.50", "2.55")
            : leg(BEAR_HIGH, "PE", "75", "5", "5.10");
        var spot = new FnoVerifiedNiftyDebitSpreadSourceStore.IndexTick(
            new BigDecimal(bull ? "125" : "75"),
            NOW.minusSeconds(40), NOW.minusSeconds(30));
        var costs = new FnoVerifiedNiftyDebitSpreadSourceStore.Risk(
            50, 50, new BigDecimal("10"), 300, 2, 50, 5000,
            new BigDecimal("10000"), 3000, 3000);
        return new FnoVerifiedNiftyDebitSpreadSourceStore.Proof(
            PROVIDER, bull ? "BULL_CALL" : "BEAR_PUT", INDEX,
            lower, higher, spot, costs,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    private static FnoVerifiedNiftyDebitSpreadSourceStore source() {
        var source = mock(FnoVerifiedNiftyDebitSpreadSourceStore.class);
        when(source.oneCurrentSpread("BULL_CALL", BULL_LOW, BULL_HIGH, NOW))
            .thenReturn(Optional.of(proof(true)));
        when(source.oneCurrentSpread("BEAR_PUT", BEAR_LOW, BEAR_HIGH, NOW))
            .thenReturn(Optional.of(proof(false)));
        return source;
    }

    private static FnoVerifiedNiftyLongOptionSourceStore daily(String finalClose) {
        var daily = mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        when(daily.verifiedRecentCloses(NOW)).thenReturn(closes(finalClose));
        return daily;
    }

    private static FnoDebitSpreadResearchService service(
            FnoVerifiedNiftyLongOptionSourceStore daily,
            FnoVerifiedNiftyDebitSpreadSourceStore spread,
            boolean rights) {
        return new FnoDebitSpreadResearchService(daily, spread, calendar(),
            rights, rights, rights, rights,
            BULL_LOW, BULL_HIGH, BEAR_LOW, BEAR_HIGH);
    }

    private static Map<String,FnoFuturesResearchService.StrategyStatus>
            check(FnoDebitSpreadResearchService source) {
        return source.evaluateAll(NOW).stream().collect(
            Collectors.toMap(FnoFuturesResearchService.StrategyStatus::strategyId, x->x));
    }

    @Test
    void historicalDebitSourceReadsOnlySelectedOriginalTwoLegOptionPair(){
        var daily=daily("125");var source=source();
        var r=service(daily,source,true).evaluateOne("strategy.STR-183",NOW);
        assertEquals("RESEARCH_CANDIDATE",r.status());
        assertEquals("VERIFIED_TWO_LEG_CE_DEBIT_SPREAD_RESEARCH_NO_ORDER",r.inputState());
        verify(daily).verifiedRecentCloses(NOW);
        verify(source).oneCurrentSpread("BULL_CALL",BULL_LOW,BULL_HIGH,NOW);
        verify(source,never()).oneCurrentSpread(eq("BEAR_PUT"),any(),any(),any());
        verifyNoMoreInteractions(source,daily);
    }

    @Test
    void deniedHistoricalDebitSourceDoesNotQueryMarket(){
        var daily=daily("125");var source=source();
        var r=service(daily,source,false).evaluateOne("strategy.STR-184",NOW);
        assertEquals("DEBIT_SPREAD_SOURCE_RIGHTS_UNVERIFIED",r.reasonCode());
        verifyNoInteractions(daily,source);
        assertThrows(IllegalArgumentException.class,()->
            service(daily,source,true).evaluateOne("strategy.STR-191",NOW));
    }

    @Test
    void bullishCrossoverAndTwoDifferentCEOptionsGiveResearchOnlyDebitSpread() {
        var daily = daily("125");
        var spreads = source();
        var rows = check(service(daily, spreads, true));
        assertEquals(2, rows.size());
        var bull = rows.get("strategy.STR-183");
        assertEquals("RESEARCH_CANDIDATE", bull.status());
        assertEquals("BULL_CALL_SPREAD_RESEARCH_SETUP_ONLY", bull.reasonCode());
        assertEquals(2, bull.contractsResolved());
        assertEquals(3, bull.freshQuotes());
        assertEquals(35, bull.historySessions());
        assertEquals("VERIFIED_TWO_LEG_CE_DEBIT_SPREAD_RESEARCH_NO_ORDER",
            bull.inputState());
        assertEquals("NO_SIGNAL", rows.get("strategy.STR-184").status());
        verify(daily, times(1)).verifiedRecentCloses(NOW);
        verify(spreads).oneCurrentSpread("BULL_CALL", BULL_LOW, BULL_HIGH, NOW);
        verify(spreads).oneCurrentSpread("BEAR_PUT", BEAR_LOW, BEAR_HIGH, NOW);
    }

    @Test
    void bearishCrossoverUsesHigherStrikeLongPutAndLowerStrikeShortPut() {
        var rows = check(service(daily("75"), source(), true));
        assertEquals("NO_SIGNAL", rows.get("strategy.STR-183").status());
        assertEquals("RESEARCH_CANDIDATE", rows.get("strategy.STR-184").status());
        assertEquals("BEAR_PUT_SPREAD_RESEARCH_SETUP_ONLY",
            rows.get("strategy.STR-184").reasonCode());
        assertEquals("VERIFIED_TWO_LEG_PE_DEBIT_SPREAD_RESEARCH_NO_ORDER",
            rows.get("strategy.STR-184").inputState());
    }

    @Test
    void missingIndependentSourceGrantsNeverInspectSourceOrClaimOptionQuotes() {
        var daily = mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        var spread = mock(FnoVerifiedNiftyDebitSpreadSourceStore.class);
        var rows = check(service(daily, spread, false));
        assertEquals("BLOCKED_RIGHTS", rows.get("strategy.STR-183").status());
        assertEquals("BLOCKED_RIGHTS", rows.get("strategy.STR-184").status());
        assertEquals(0, rows.get("strategy.STR-183").freshQuotes());
        assertEquals(0, rows.get("strategy.STR-184").contractsResolved());
        verifyNoInteractions(daily,spread);
    }

    @Test
    void absentSecondLegOrVerifiedCostsCannotBeReplacedBySingleCallQuote() {
        var spread = source();
        when(spread.oneCurrentSpread("BULL_CALL", BULL_LOW, BULL_HIGH, NOW))
            .thenReturn(Optional.empty());
        var rows = check(service(daily("125"), spread, true));
        assertEquals("BLOCKED_DATA", rows.get("strategy.STR-183").status());
        assertEquals("DEBIT_SPREAD_TWO_LEG_QUOTES_COSTS_OR_DEPTH_PROOF_MISSING",
            rows.get("strategy.STR-183").reasonCode());
        assertEquals(0, rows.get("strategy.STR-183").contractsResolved());
        assertEquals(0, rows.get("strategy.STR-183").freshQuotes());
        assertEquals("NO_SIGNAL", rows.get("strategy.STR-184").status());
    }

    @Test
    void staleCandleSeriesWithMissingTradingDateBlocksBothSpreads() {
        var daily = daily("125");
        var missingDate = new ArrayList<>(closes("125"));
        missingDate.remove(19);
        when(daily.verifiedRecentCloses(NOW)).thenReturn(missingDate);
        var spread = mock(FnoVerifiedNiftyDebitSpreadSourceStore.class);
        var rows = check(service(daily,spread,true));
        assertEquals("BLOCKED_DATA", rows.get("strategy.STR-183").status());
        assertEquals("DEBIT_SPREAD_35_COMPLETED_CLOSES_INVALID",
            rows.get("strategy.STR-184").reasonCode());
        verifyNoInteractions(spread);
    }

    @Test
    void wrongProviderOnSecondLegProofFailsClosed() {
        var spread = source();
        var p = proof(true);
        var wrong = new FnoVerifiedNiftyDebitSpreadSourceStore.Proof(
            "ANGEL_ONE_SMARTAPI", p.spreadType(), p.underlyingKey(),
            p.lower(), p.higher(), p.index(), p.risk(), p.contractMasterDigest());
        when(spread.oneCurrentSpread("BULL_CALL",BULL_LOW,BULL_HIGH,NOW))
            .thenReturn(Optional.of(wrong));
        var rows = check(service(daily("125"), spread, true));
        assertEquals("DEBIT_SPREAD_PROVIDER_CONTRACT_OR_FIRST_KNOWN_MISMATCH",
            rows.get("strategy.STR-183").reasonCode());
    }

    @Test
    void confidentialProviderExceptionsDoNotLeakOrEnablePaperOrLive() {
        var spreads = source();
        when(spreads.oneCurrentSpread("BULL_CALL",BULL_LOW,BULL_HIGH,NOW))
            .thenThrow(new IllegalStateException("secret option chain token"));
        var rows = check(service(daily("125"), spreads, true));
        assertEquals("BLOCKED_DATA", rows.get("strategy.STR-183").status());
        assertEquals("DEBIT_SPREAD_SOURCE_READ_UNAVAILABLE",
            rows.get("strategy.STR-183").reasonCode());
        assertFalse(rows.get("strategy.STR-183").toString().contains("secret"));
    }
}
