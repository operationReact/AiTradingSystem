package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongButterflySourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongButterflySourceStore.*;
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

/** Deterministic synthetic fixtures, not independent broker or NSE E2E verification. */
class FnoLongButterflyResearchServiceTest {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final String SOURCE="UPSTOX_ANALYTICS";
    static final String LOW="NSE_FO|TEST_20261127_90_CE";
    static final String MID="NSE_FO|TEST_20261127_100_CE";
    static final String HIGH="NSE_FO|TEST_20261127_110_CE";
    static BigDecimal d(String n){return new BigDecimal(n);}
    static MarketSessionCalendar calendar(){
        var c=mock(MarketSessionCalendar.class);
        when(c.isInsideCashSession(NOW)).thenReturn(true);
        when(c.isTradingDay(any(LocalDate.class))).thenAnswer(i->
            ((LocalDate)i.getArgument(0)).getDayOfWeek().getValue()<=5);
        return c;
    }
    static List<VerifiedClose> history(){
        var days=new ArrayList<LocalDate>();var day=LocalDate.of(2026,11,19);
        while(days.size()<35){
            if(day.getDayOfWeek().getValue()<=5)days.add(0,day);
            day=day.minusDays(1);
        }
        var out=new ArrayList<VerifiedClose>();
        for(var date:days){
            var close=date.atTime(15,30).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            out.add(new VerifiedClose(SOURCE,"NSE_INDEX|Nifty 50",date,
                d("100"),close.plusSeconds(60),close));
        }
        return out;
    }
    static FnoVerifiedNiftyLongOptionSourceStore daily(){
        var store=mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        when(store.verifiedRecentCloses(NOW)).thenReturn(history());
        return store;
    }
    static Leg leg(String key,String strike,String bid,String ask,long bd,long ad){
        Instant x=NOW.minusSeconds(40);
        return new Leg(key,d(strike),LocalDate.of(2026,11,1),
            d(bid),d(ask),d("5"),bd,ad,1000,x,x.plusSeconds(10));
    }
    static Proof proof(){
        Instant x=NOW.minusSeconds(40);
        return new Proof(SOURCE,LocalDate.of(2026,11,27),50,d("0.05"),
            new Index(d("100"),d("100.05"),x,x.plusSeconds(10)),
            leg(LOW,"90","9.90","10",10,10),
            leg(MID,"100","6","6.1",10,10),
            leg(HIGH,"110","3.95","4",10,10),
            new Budget(50,50,300,2,50,10000,500,300,
                d("20"),d("10000"),1000,1000,8000),
            x.plusSeconds(10));
    }
    static FnoVerifiedNiftyLongButterflySourceStore source(){
        var s=mock(FnoVerifiedNiftyLongButterflySourceStore.class);
        when(s.oneCurrent(LOW,MID,HIGH,NOW)).thenReturn(Optional.of(proof()));
        return s;
    }
    static FnoLongButterflyResearchService service(
        FnoVerifiedNiftyLongOptionSourceStore daily,
        FnoVerifiedNiftyLongButterflySourceStore source,boolean rights){
        return new FnoLongButterflyResearchService(daily,source,calendar(),
            rights,rights,rights,rights,rights,LOW,MID,HIGH);
    }
    @Test void verifiedOneBuyTwoSellOneBuyUsesActualThreeOptionContracts(){
        var d=daily();var s=source();
        var result=service(d,s,true).evaluate(NOW);
        assertEquals("strategy.STR-198",result.strategyId());
        assertEquals("RESEARCH_CANDIDATE",result.status());
        assertEquals("LONG_BUTTERFLY_RESEARCH_ONLY",result.reasonCode());
        assertEquals(3,result.contractsResolved());
        assertEquals(4,result.freshQuotes());
        assertEquals(35,result.historySessions());
        assertEquals("ORIGINAL_THREE_CALL_TWO_CENTER_SHORT_LOTS_NO_ORDER",result.inputState());
        verify(s).oneCurrent(LOW,MID,HIGH,NOW);
        var blocked=FnoLongButterflyV1.evaluate(null,null,null,List.of(),
            null,null,null,null,new FnoLongButterflyV1.Rights(false,false,false,false),
            null,NOW);
        assertTrue(blocked.expiryRiskBoundedOnlyWhenAllLegsIntact());
        assertFalse(blocked.orderPlaced());assertFalse(blocked.executable());
        assertFalse(blocked.liveTradeAuthorized());
    }
    @Test void missingOriginalResearchRightsNeverReadsMarketData(){
        var d=mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        var s=mock(FnoVerifiedNiftyLongButterflySourceStore.class);
        var result=service(d,s,false).evaluate(NOW);
        assertEquals("BLOCKED_RIGHTS",result.status());
        assertEquals("LONG_BUTTERFLY_SOURCE_RIGHTS_UNVERIFIED",result.reasonCode());
        verifyNoInteractions(d,s);
    }
    @Test void absentOriginalThreeIvQuotesCannotBeInferred(){
        var s=source();when(s.oneCurrent(LOW,MID,HIGH,NOW)).thenReturn(Optional.empty());
        assertEquals("LONG_BUTTERFLY_THREE_VERIFIED_IV_QUOTES_MISSING",
            service(daily(),s,true).evaluate(NOW).reasonCode());
    }
    @Test void asymmetricWingStrikeCannotBecomeSourceCandidate(){
        var p=proof();var s=source();
        when(s.oneCurrent(LOW,MID,HIGH,NOW)).thenReturn(Optional.of(new Proof(
            p.provider(),p.expiry(),p.lotSize(),p.tick(),p.index(),
            p.lower(),p.center(),leg(HIGH,"115","3.95","4",10,10),
            p.budget(),p.firstKnownAt())));
        assertEquals("LONG_BUTTERFLY_CONTRACT_TIME_DEPTH_OR_IV_INVALID",
            service(daily(),s,true).evaluate(NOW).reasonCode());
    }
    @Test void centerNeedsDoubleActualBidLotsNotOne(){
        var p=proof();var s=source();
        when(s.oneCurrent(LOW,MID,HIGH,NOW)).thenReturn(Optional.of(new Proof(
            p.provider(),p.expiry(),p.lotSize(),p.tick(),p.index(),
            p.lower(),leg(MID,"100","6","6.1",3,10),p.upper(),
            p.budget(),p.firstKnownAt())));
        assertEquals("LONG_BUTTERFLY_CONTRACT_TIME_DEPTH_OR_IV_INVALID",
            service(daily(),s,true).evaluate(NOW).reasonCode());
    }
    @Test void missingProtectiveAskDepthOrProviderIvBlocksBeforeModel(){
        var p=proof();var s=source();
        when(s.oneCurrent(LOW,MID,HIGH,NOW)).thenReturn(Optional.of(new Proof(
            p.provider(),p.expiry(),p.lotSize(),p.tick(),p.index(),
            leg(LOW,"90","9.9","10",10,0),p.center(),p.upper(),
            p.budget(),p.firstKnownAt())));
        assertEquals("LONG_BUTTERFLY_CONTRACT_TIME_DEPTH_OR_IV_INVALID",
            service(daily(),s,true).evaluate(NOW).reasonCode());
        var noIv=new Leg(MID,d("100"),LocalDate.of(2026,11,1),
            d("6"),d("6.1"),d("0"),10,10,1000,
            NOW.minusSeconds(40),NOW.minusSeconds(30));
        when(s.oneCurrent(LOW,MID,HIGH,NOW)).thenReturn(Optional.of(new Proof(
            p.provider(),p.expiry(),p.lotSize(),p.tick(),p.index(),
            p.lower(),noIv,p.upper(),p.budget(),p.firstKnownAt())));
        assertEquals("LONG_BUTTERFLY_CONTRACT_TIME_DEPTH_OR_IV_INVALID",
            service(daily(),s,true).evaluate(NOW).reasonCode());
    }
    @Test void crossProviderAndTooLittleRealDailyHistoryBlock(){
        var p=proof();var s=source();
        when(s.oneCurrent(LOW,MID,HIGH,NOW)).thenReturn(Optional.of(new Proof(
            "ANGEL_ONE_SMARTAPI",p.expiry(),p.lotSize(),p.tick(),p.index(),
            p.lower(),p.center(),p.upper(),p.budget(),p.firstKnownAt())));
        assertEquals("LONG_BUTTERFLY_CONTRACT_TIME_DEPTH_OR_IV_INVALID",
            service(daily(),s,true).evaluate(NOW).reasonCode());
        var daily=daily();when(daily.verifiedRecentCloses(NOW)).thenReturn(history().subList(0,4));
        assertEquals("LONG_BUTTERFLY_35_VERIFIED_CLOSES_MISSING",
            service(daily,source(),true).evaluate(NOW).reasonCode());
    }
    @Test void sourceExceptionDoesNotLeakProviderSecretsOrAuthorizeOrders(){
        var s=source();when(s.oneCurrent(LOW,MID,HIGH,NOW)).thenThrow(
            new IllegalStateException("secret SSM token"));
        var row=service(daily(),s,true).evaluate(NOW);
        assertEquals("LONG_BUTTERFLY_ORIGINAL_THREE_CALL_SOURCE_READ_FAILED",
            row.reasonCode());
        assertFalse(row.toString().contains("secret"));
    }
}
