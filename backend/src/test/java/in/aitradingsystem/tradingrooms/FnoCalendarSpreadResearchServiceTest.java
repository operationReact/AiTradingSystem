package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyCalendarSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyCalendarSourceStore.*;
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

/** Deterministic fixtures do NOT constitute real NSE market-data E2E proof. */
class FnoCalendarSpreadResearchServiceTest {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final String SOURCE="UPSTOX_ANALYTICS";
    static final String NEAR="NSE_FO|TEST_20261127_100_CE";
    static final String FAR="NSE_FO|TEST_20261224_100_CE";
    static BigDecimal d(String x){return new BigDecimal(x);}
    static MarketSessionCalendar calendar(){
        var c=mock(MarketSessionCalendar.class);
        when(c.isInsideCashSession(NOW)).thenReturn(true);
        when(c.isTradingDay(any(LocalDate.class))).thenAnswer(i->
            ((LocalDate)i.getArgument(0)).getDayOfWeek().getValue()<=5);
        return c;
    }
    static List<VerifiedClose> history(){
        var dates=new ArrayList<LocalDate>();
        var at=LocalDate.of(2026,11,19);
        while(dates.size()<35){
            if(at.getDayOfWeek().getValue()<=5)dates.add(0,at);
            at=at.minusDays(1);
        }
        var out=new ArrayList<VerifiedClose>();
        for(var day:dates){
            var close=day.atTime(15,30).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            out.add(new VerifiedClose(SOURCE,"NSE_INDEX|Nifty 50",day,d("100"),
                close.plusSeconds(60),close));
        }
        return out;
    }
    static FnoVerifiedNiftyLongOptionSourceStore daily(){
        var d=mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        when(d.verifiedRecentCloses(NOW)).thenReturn(history());
        return d;
    }
    static Leg leg(String key,String date,String bid,String ask,String iv,long bd,long ad){
        var at=NOW.minusSeconds(40);
        return new Leg(key,LocalDate.parse(date),LocalDate.of(2026,11,1),
            d(bid),d(ask),d(iv),bd,ad,1000,at,at.plusSeconds(10));
    }
    static Proof proof(){
        var at=NOW.minusSeconds(40);
        return new Proof(SOURCE,"CE",d("100"),d("0.05"),50,
            new Index(d("100"),d("100.05"),at,at.plusSeconds(10)),
            leg(NEAR,"2026-11-27","3.95","4","40",10,10),
            leg(FAR,"2026-12-24","5.95","6","35",10,10),
            new Budget(50,50,300,2,50,1500,300,d("20"),d("10000"),
                1000,9000,5000,2000),at.plusSeconds(10));
    }
    static FnoVerifiedNiftyCalendarSourceStore source(){
        var s=mock(FnoVerifiedNiftyCalendarSourceStore.class);
        when(s.oneCurrent(NEAR,FAR,"CE",NOW)).thenReturn(Optional.of(proof()));
        return s;
    }
    static FnoCalendarSpreadResearchService service(
        FnoVerifiedNiftyLongOptionSourceStore daily,
        FnoVerifiedNiftyCalendarSourceStore store,boolean rights){
        return new FnoCalendarSpreadResearchService(daily,store,calendar(),
            rights,rights,rights,rights,rights,NEAR,FAR,"CE");
    }
    @Test void trueDifferentExpiriesInvokeOriginalTypedEvaluator(){
        var store=source();
        var result=service(daily(),store,true).evaluate(NOW);
        assertEquals("strategy.STR-199",result.strategyId());
        assertEquals("RESEARCH_CANDIDATE",result.status());
        assertEquals("CALENDAR_SPREAD_MARK_TO_MARKET_UNRESOLVED_RESEARCH_ONLY",result.reasonCode());
        assertEquals(2,result.contractsResolved());
        assertEquals(3,result.freshQuotes());
        assertEquals(35,result.historySessions());
        assertEquals("ORIGINAL_DIFFERENT_EXPIRIES_FAR_MARK_UNRESOLVED_NO_ORDER",
            result.inputState());
        verify(store).oneCurrent(NEAR,FAR,"CE",NOW);
        var safe=FnoCalendarSpreadV1.evaluate(null,null,List.of(),null,null,null,
            new FnoCalendarSpreadV1.Rights(false,false,false,false),null,NOW);
        assertTrue(safe.nearExpiryResidualLongMarkUnknown());
        assertFalse(safe.executable());assertFalse(safe.orderPlaced());
        assertFalse(safe.liveTradeAuthorized());
    }
    @Test void missingAnySourceRightsCannotQueryBrokerOrIndex(){
        var daily=mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        var store=mock(FnoVerifiedNiftyCalendarSourceStore.class);
        var row=service(daily,store,false).evaluate(NOW);
        assertEquals("BLOCKED_RIGHTS",row.status());
        assertEquals("CALENDAR_ORIGINAL_SOURCE_RIGHTS_UNVERIFIED",row.reasonCode());
        verifyNoInteractions(daily,store);
    }
    @Test void noNearFarSourceMustNeverSynthesizeIv(){
        var store=source();
        when(store.oneCurrent(NEAR,FAR,"CE",NOW)).thenReturn(Optional.empty());
        assertEquals("CALENDAR_INDEPENDENT_NEAR_FAR_IV_QUOTES_MISSING",
            service(daily(),store,true).evaluate(NOW).reasonCode());
    }
    @Test void sameExpiryNeverBecomesCalendar(){
        var p=proof();var store=source();
        when(store.oneCurrent(NEAR,FAR,"CE",NOW)).thenReturn(Optional.of(new Proof(
            p.provider(),p.optionType(),p.strike(),p.tick(),p.lotSize(),
            p.index(),p.near(),
            leg(FAR,"2026-11-27","5.95","6","35",10,10),
            p.budget(),p.firstKnownAt())));
        assertEquals("CALENDAR_EXPIRY_QUOTE_OR_IV_PROVENANCE_INVALID",
            service(daily(),store,true).evaluate(NOW).reasonCode());
    }
    @Test void nearRealBidAndFarRealAskDepthMustExist(){
        var p=proof();var store=source();
        when(store.oneCurrent(NEAR,FAR,"CE",NOW)).thenReturn(Optional.of(new Proof(
            p.provider(),p.optionType(),p.strike(),p.tick(),p.lotSize(),
            p.index(),leg(NEAR,"2026-11-27","3.95","4","40",0,10),
            p.far(),p.budget(),p.firstKnownAt())));
        assertEquals("CALENDAR_EXPIRY_QUOTE_OR_IV_PROVENANCE_INVALID",
            service(daily(),store,true).evaluate(NOW).reasonCode());
        when(store.oneCurrent(NEAR,FAR,"CE",NOW)).thenReturn(Optional.of(new Proof(
            p.provider(),p.optionType(),p.strike(),p.tick(),p.lotSize(),
            p.index(),p.near(),
            leg(FAR,"2026-12-24","5.95","6","35",10,0),
            p.budget(),p.firstKnownAt())));
        assertEquals("CALENDAR_EXPIRY_QUOTE_OR_IV_PROVENANCE_INVALID",
            service(daily(),store,true).evaluate(NOW).reasonCode());
    }
    @Test void independentlyVerifiedOriginalIvRequiredOnBothExpiries(){
        var p=proof();var store=source();
        when(store.oneCurrent(NEAR,FAR,"CE",NOW)).thenReturn(Optional.of(new Proof(
            p.provider(),p.optionType(),p.strike(),p.tick(),p.lotSize(),
            p.index(),leg(NEAR,"2026-11-27","3.95","4","0",10,10),
            p.far(),p.budget(),p.firstKnownAt())));
        assertEquals("CALENDAR_EXPIRY_QUOTE_OR_IV_PROVENANCE_INVALID",
            service(daily(),store,true).evaluate(NOW).reasonCode());
    }
    @Test void wrongProviderAndInsufficientHistoricalNiftyFailClosed(){
        var p=proof();var store=source();
        when(store.oneCurrent(NEAR,FAR,"CE",NOW)).thenReturn(Optional.of(new Proof(
            "ANGEL_ONE_SMARTAPI",p.optionType(),p.strike(),p.tick(),p.lotSize(),
            p.index(),p.near(),p.far(),p.budget(),p.firstKnownAt())));
        assertEquals("CALENDAR_EXPIRY_QUOTE_OR_IV_PROVENANCE_INVALID",
            service(daily(),store,true).evaluate(NOW).reasonCode());
        var d=daily();
        when(d.verifiedRecentCloses(NOW)).thenReturn(history().subList(0,4));
        assertEquals("CALENDAR_35_VERIFIED_INDEX_CLOSES_MISSING",
            service(d,source(),true).evaluate(NOW).reasonCode());
    }
    @Test void providerExceptionDoesNotLeakAccessTokens(){
        var store=source();
        when(store.oneCurrent(NEAR,FAR,"CE",NOW)).thenThrow(new IllegalStateException("secret token"));
        var row=service(daily(),store,true).evaluate(NOW);
        assertEquals("CALENDAR_ORIGINAL_TWO_EXPIRY_SOURCE_READ_FAILED",row.reasonCode());
        assertFalse(row.toString().contains("secret"));
    }
}
