package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyIronButterflySourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyIronButterflySourceStore.*;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Synthetic fixture tests; never real market-source or execution validation. */
class FnoIronButterflyResearchServiceTest {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final String SOURCE="UPSTOX_ANALYTICS";
    static final String LP="NSE_FO|TEST_20261127_80_PE";
    static final String SP="NSE_FO|TEST_20261127_100_PE";
    static final String SC="NSE_FO|TEST_20261127_100_CE";
    static final String LC="NSE_FO|TEST_20261127_120_CE";
    static BigDecimal d(String s){return new BigDecimal(s);}
    static MarketSessionCalendar calendar(){
        var c=mock(MarketSessionCalendar.class);
        when(c.isInsideCashSession(NOW)).thenReturn(true);
        when(c.isTradingDay(any(LocalDate.class))).thenAnswer(i->
            ((LocalDate)i.getArgument(0)).getDayOfWeek().getValue()<=5);
        return c;
    }
    static List<VerifiedClose> history(){
        var days=new ArrayList<LocalDate>();
        var day=LocalDate.of(2026,11,19);
        while(days.size()<35){
            if(day.getDayOfWeek().getValue()<=5)days.add(0,day);
            day=day.minusDays(1);
        }
        var out=new ArrayList<VerifiedClose>();
        for(var date:days){
            var close=date.atTime(15,30).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            out.add(new VerifiedClose(SOURCE,"NSE_INDEX|Nifty 50",date,d("100"),
                close.plusSeconds(60),close));
        }
        return out;
    }
    static FnoVerifiedNiftyLongOptionSourceStore daily(){
        var store=mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        when(store.verifiedRecentCloses(NOW)).thenReturn(history());
        return store;
    }
    static Leg leg(String key,String strike,String bid,String ask){
        var exchange=NOW.minusSeconds(40);
        return new Leg(key,d(strike),LocalDate.of(2026,11,1),
            d(bid),d(ask),d("5"),10,10,1000,
            exchange,exchange.plusSeconds(10));
    }
    static Proof proof(){
        var exchange=NOW.minusSeconds(40);
        return new Proof(SOURCE,LocalDate.of(2026,11,27),50,d("0.05"),
            new Index(d("100"),d("100.1"),exchange,exchange.plusSeconds(10)),
            leg(LP,"80","0.99","1"),
            leg(SP,"100","4","4.05"),
            leg(SC,"100","4","4.05"),
            leg(LC,"120","0.99","1"),
            new Budget(50,50,300,2,50,10000,500,300,d("20"),d("10000"),
                1000,1000,10000),
            exchange.plusSeconds(10));
    }
    static FnoVerifiedNiftyIronButterflySourceStore source(){
        var store=mock(FnoVerifiedNiftyIronButterflySourceStore.class);
        when(store.oneCurrent(LP,SP,SC,LC,NOW)).thenReturn(Optional.of(proof()));
        return store;
    }
    static FnoIronButterflyResearchService service(
        FnoVerifiedNiftyLongOptionSourceStore daily,
        FnoVerifiedNiftyIronButterflySourceStore source,boolean rights){
        return new FnoIronButterflyResearchService(daily,source,calendar(),
            rights,rights,rights,rights,rights,LP,SP,SC,LC);
    }
    static void assertNoOrder(FnoIronButterflyV1.Decision d) {
        assertFalse(d.executable());assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
        assertTrue(d.expiryPayoffDefinedRisk());
    }
    @Test void originalFourLegQuoteInvokesExistingTypedIronCondorEvaluator(){
        var daily=daily();var store=source();
        var r=service(daily,store,true).evaluate(NOW);
        assertEquals("strategy.STR-197",r.strategyId());
        assertEquals("RESEARCH_CANDIDATE",r.status());
        assertEquals("IRON_BUTTERFLY_DEFINED_EXPIRY_RISK_RESEARCH_ONLY",r.reasonCode());
        assertEquals(4,r.contractsResolved());
        assertEquals(5,r.freshQuotes());
        assertEquals(35,r.historySessions());
        assertEquals("ORIGINAL_FOUR_LEG_SOURCE_DEFINED_EXPIRY_RISK_ONLY_NO_ORDER",
            r.inputState());
        verify(store).oneCurrent(LP,SP,SC,LC,NOW);
        assertNoOrder(FnoIronButterflyV1.evaluate(null,null,null,null,List.of(),
            null,null,null,null,null,new FnoIronButterflyV1.Rights(false,false,false,false),
            null,NOW));
    }
    @Test void missingRightsCannotReadSourceOrHistory(){
        var d=mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        var s=mock(FnoVerifiedNiftyIronButterflySourceStore.class);
        var row=service(d,s,false).evaluate(NOW);
        assertEquals("BLOCKED_RIGHTS",row.status());
        assertEquals("IRON_BUTTERFLY_SOURCE_RIGHTS_UNVERIFIED",row.reasonCode());
        verifyNoInteractions(d,s);
    }
    @Test void emptyEvidenceCannotGenerateSyntheticQuotes(){
        var s=source();
        when(s.oneCurrent(LP,SP,SC,LC,NOW)).thenReturn(Optional.empty());
        assertEquals("IRON_BUTTERFLY_FOUR_INDEPENDENT_IV_QUOTES_MISSING",
            service(daily(),s,true).evaluate(NOW).reasonCode());
    }
    @Test void wrongCentralStrikeStructureBlocks(){
        var p=proof();var s=source();
        when(s.oneCurrent(LP,SP,SC,LC,NOW)).thenReturn(Optional.of(new Proof(
            p.provider(),p.expiry(),p.lotSize(),p.tick(),p.index(),
            p.longPut(),leg(SP,"90","4","4.05"),p.shortCall(),p.longCall(),
            p.budget(),p.firstKnownAt())));
        assertEquals("IRON_BUTTERFLY_FOUR_LEG_CONTRACT_QUOTE_OR_PROVENANCE_INVALID",
            service(daily(),s,true).evaluate(NOW).reasonCode());
    }
    @Test void weakBidForShortAndWeakAskForProtectiveLegsFailClosed(){
        var p=proof();var s=source();
        when(s.oneCurrent(LP,SP,SC,LC,NOW)).thenReturn(Optional.of(new Proof(
            p.provider(),p.expiry(),p.lotSize(),p.tick(),p.index(),
            p.longPut(),new Leg(SP,d("90"),LocalDate.of(2026,11,1),
                d("4"),d("4.05"),d("5"),0,10,1000,
                NOW.minusSeconds(40),NOW.minusSeconds(30)),
            p.shortCall(),p.longCall(),p.budget(),p.firstKnownAt())));
        assertEquals("IRON_BUTTERFLY_FOUR_LEG_CONTRACT_QUOTE_OR_PROVENANCE_INVALID",
            service(daily(),s,true).evaluate(NOW).reasonCode());
        when(s.oneCurrent(LP,SP,SC,LC,NOW)).thenReturn(Optional.of(new Proof(
            p.provider(),p.expiry(),p.lotSize(),p.tick(),p.index(),
            new Leg(LP,d("80"),LocalDate.of(2026,11,1),
                d("0.99"),d("1"),d("5"),10,0,1000,
                NOW.minusSeconds(40),NOW.minusSeconds(30)),
            p.shortPut(),p.shortCall(),p.longCall(),p.budget(),p.firstKnownAt())));
        assertEquals("IRON_BUTTERFLY_FOUR_LEG_CONTRACT_QUOTE_OR_PROVENANCE_INVALID",
            service(daily(),s,true).evaluate(NOW).reasonCode());
    }
    @Test void originalIvCannotBeMissingOrEstimated(){
        var p=proof();var s=source();
        when(s.oneCurrent(LP,SP,SC,LC,NOW)).thenReturn(Optional.of(new Proof(
            p.provider(),p.expiry(),p.lotSize(),p.tick(),p.index(),
            p.longPut(),new Leg(SP,d("90"),LocalDate.of(2026,11,1),
                d("4"),d("4.05"),d("0"),10,10,1000,
                NOW.minusSeconds(40),NOW.minusSeconds(30)),
            p.shortCall(),p.longCall(),p.budget(),p.firstKnownAt())));
        assertEquals("IRON_BUTTERFLY_FOUR_LEG_CONTRACT_QUOTE_OR_PROVENANCE_INVALID",
            service(daily(),s,true).evaluate(NOW).reasonCode());
    }
    @Test void anyProviderMismatchOrStaleSourceFailsClosed(){
        var p=proof();var s=source();
        when(s.oneCurrent(LP,SP,SC,LC,NOW)).thenReturn(Optional.of(new Proof(
            "ANGEL_ONE_SMARTAPI",p.expiry(),p.lotSize(),p.tick(),p.index(),
            p.longPut(),p.shortPut(),p.shortCall(),p.longCall(),
            p.budget(),p.firstKnownAt())));
        assertEquals("IRON_BUTTERFLY_FOUR_LEG_CONTRACT_QUOTE_OR_PROVENANCE_INVALID",
            service(daily(),s,true).evaluate(NOW).reasonCode());
    }
    @Test void insufficientHistoryAndFailuresNeverLeakProviderSecrets(){
        var d=daily();var s=source();
        when(d.verifiedRecentCloses(NOW)).thenReturn(history().subList(0,4));
        assertEquals("IRON_BUTTERFLY_35_VERIFIED_COMPLETED_CLOSES_MISSING",
            service(d,s,true).evaluate(NOW).reasonCode());
        when(d.verifiedRecentCloses(NOW)).thenReturn(history());
        when(s.oneCurrent(LP,SP,SC,LC,NOW)).thenThrow(
            new IllegalStateException("secret broker token"));
        var failure=service(d,s,true).evaluate(NOW);
        assertEquals("IRON_BUTTERFLY_ORIGINAL_FOUR_LEG_SOURCE_READ_FAILED",
            failure.reasonCode());
        assertFalse(failure.toString().contains("secret"));
    }

    @Test void twoCentralShortContractsMustShareExactStrike(){
        var p=proof();var store=source();
        when(store.oneCurrent(LP,SP,SC,LC,NOW)).thenReturn(Optional.of(new Proof(
            p.provider(),p.expiry(),p.lotSize(),p.tick(),p.index(),
            p.longPut(),p.shortPut(),leg(SC,"110","4","4.05"),
            p.longCall(),p.budget(),p.firstKnownAt())));
        assertEquals("IRON_BUTTERFLY_FOUR_LEG_CONTRACT_QUOTE_OR_PROVENANCE_INVALID",
            service(daily(),store,true).evaluate(NOW).reasonCode());
    }
}
