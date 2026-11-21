package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import in.aitradingsystem.tradingrooms.FnoExpiryRiskNeutralizationV1.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoExpiryRiskNeutralizationV1Test {
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant EX=Instant.parse("2026-11-20T04:59:20Z");
    private static final Instant FIRST=Instant.parse("2026-11-20T04:59:24Z");
    private static final Instant BOOK=Instant.parse("2026-11-19T09:00:00Z");
    private static final Instant SCHEDULE=Instant.parse("2026-11-19T08:00:00Z");
    private static final Instant CUTOFF=Instant.parse("2026-11-20T10:00:00Z");
    private static final LocalDate EXPIRY=LocalDate.of(2026,11,20);
    private static final String SRC="UPSTOX_ANALYTICS";
    private static final String INDEX="NSE_INDEX|Nifty 50";
    private static final String PE="NSE_FO|NIFTY_SYNTH_100_PE";
    private static final String CE="NSE_FO|NIFTY_SYNTH_105_CE";
    private static final String CE_CAP="NSE_FO|NIFTY_SYNTH_110_CE";
    private static BigDecimal n(String x) {return new BigDecimal(x);}

    private static Rights rights(){return new Rights(true,true,true,true,true,true);}
    private static Policy policy(){
        return new Policy(180,30,15,360,500,10,300,10,10,10,10,n("5"),n("1000"));
    }
    private static Contract contract(String key,String type,String strike){
        return new Contract(key,INDEX,type,n(strike),n("0.05"),
                LocalDate.of(2026,11,1),EXPIRY,50,true);
    }
    private static Contract put(){return contract(PE,"PE","100");}
    private static Contract call(){return contract(CE,"CE","105");}
    private static Contract capCall(){return contract(CE_CAP,"CE","110");}
    private static Position position(Contract c,int signed){
        return new Position(c,signed,BOOK,true);
    }
    private static BrokerBook book(){
        return new BrokerBook(true,BOOK,List.of(position(put(),2),position(call(),-1)));
    }
    private static Quote quote(String key,String type,String bid,String ask,long bidDepth,
                               long askDepth,String provider,Instant ex,Instant known){
        return new Quote(key,provider,n(bid),n(ask),n("PE".equals(type)?"-0.5":"0.3"),
                n("PE".equals(type)?"0.02":"0.015"),n("26"),1000,bidDepth,
                askDepth,ex,known,true);
    }
    private static Quote peQuote(){
        return quote(PE,"PE","2.95","3",10,10,SRC,EX,FIRST);
    }
    private static Quote ceQuote(){
        return quote(CE,"CE","4","4.05",10,10,SRC,EX,FIRST);
    }
    private static Snapshot snap(List<Quote> quotes){
        return new Snapshot(SRC,INDEX,n("100"),quotes,EX,FIRST,true);
    }
    private static Snapshot snapshot(){
        return snap(List.of(peQuote(),ceQuote()));
    }
    private static ExpiryEvidence expiry(Instant cutoff){
        return new ExpiryEvidence(SRC,INDEX,EXPIRY,cutoff,SCHEDULE,true);
    }
    private static ExpiryEvidence expiry(){return expiry(CUTOFF);}
    private static List<CompletedClose> history(){
        var days=new ArrayList<LocalDate>();
        LocalDate day=LocalDate.of(2026,11,19);
        while(days.size()<35){
            if(day.getDayOfWeek().getValue()<=5)days.add(0,day);
            day=day.minusDays(1);
        }
        var result=new ArrayList<CompletedClose>();
        for(LocalDate d:days){
            Instant close=d.atTime(LocalTime.of(15,30)).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            result.add(new CompletedClose(d,SRC,n("100"),close,close.plusSeconds(60),true));
        }
        return result;
    }
    private static Decision eval(BrokerBook b,Snapshot s,ExpiryEvidence e,
                                 List<CompletedClose> bars,Rights r,Policy p){
        return FnoExpiryRiskNeutralizationV1.evaluate(b,s,e,bars,r,p,NOW);
    }
    private static Decision run(){return eval(book(),snapshot(),expiry(),history(),rights(),policy());}
    private static void neverOrders(Decision d){
        assertEquals("strategy.STR-209",d.strategyId());
        assertTrue(d.fullExpiryBookOnly());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.paperTradeAuthorized());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test void completeExistingOptionBookProducesOnlyHypotheticalClose(){
        Decision d=run();
        assertEquals(Status.RESEARCH_CLOSE_ALL,d.status());
        assertEquals(300,d.minutesBeforeOfficialCutoff());
        assertEquals(2,d.authenticatedBookContracts());
        assertEquals(3,d.totalHypotheticalClosingLots());
        assertEquals(0,n("-65").compareTo(d.originalNetOptionDeltaUnits()));
        assertEquals(0,n("1.25").compareTo(d.originalNetOptionGammaUnitsPerPoint()));
        assertEquals(0,n("0").compareTo(d.closestStrikeDistanceBps()));
        assertEquals(0,n("-82.0025").compareTo(d.hypotheticalNetCloseDebitInr()));
        assertEquals(0,n("10000").compareTo(d.grossExpiryIntrinsicAtZeroInr()));
        assertEquals(0,n("0").compareTo(d.grossExpiryIntrinsicAtSpotInr()));
        assertEquals(0,n("2000").compareTo(d.grossExpiryIntrinsicAtSpotMinus20PercentInr()));
        assertEquals(0,n("-750").compareTo(d.grossExpiryIntrinsicAtSpotPlus20PercentInr()));
        assertTrue(d.potentiallyUnlimitedUpsideShortCallLiability());
        neverOrders(d);
    }
    @Test void eachIndependentResearchRightRequired(){
        Decision d=eval(book(),snapshot(),expiry(),history(),
                new Rights(true,true,true,false,true,true),policy());
        assertEquals(Status.BLOCKED_RIGHTS,d.status());
        neverOrders(d);
    }
    @Test void noUnverifiedOrPartialBrokerBookIsConsideredComplete(){
        var b=new BrokerBook(false,BOOK,book().positions());
        assertEquals(Status.BLOCKED_DATA,eval(b,snapshot(),expiry(),history(),rights(),policy()).status());
        b=new BrokerBook(true,BOOK,List.of());
        assertEquals(Status.BLOCKED_DATA,eval(b,snapshot(),expiry(),history(),rights(),policy()).status());
    }
    @Test void rejectMissingOrUnverifiedBrokerPositionAndWrongOriginalTime(){
        var p=new Position(put(),2,BOOK,false);
        var b=new BrokerBook(true,BOOK,List.of(p,position(call(),-1)));
        assertEquals(Status.BLOCKED_DATA,eval(b,snapshot(),expiry(),history(),rights(),policy()).status());
        b=new BrokerBook(true,BOOK,List.of(new Position(put(),2,NOW,true),position(call(),-1)));
        assertEquals(Status.BLOCKED_DATA,eval(b,snapshot(),expiry(),history(),rights(),policy()).status());
        b=new BrokerBook(true,NOW,book().positions());
        assertEquals(Status.BLOCKED_DATA,eval(b,snapshot(),expiry(),history(),rights(),policy()).status());
    }
    @Test void zeroAndDuplicateBrokerContractsAreNotNeutralization(){
        var b=new BrokerBook(true,BOOK,List.of(position(put(),0),position(call(),-1)));
        assertEquals(Status.BLOCKED_DATA,eval(b,snapshot(),expiry(),history(),rights(),policy()).status());
        b=new BrokerBook(true,BOOK,List.of(position(put(),2),position(put(),1)));
        assertEquals(Status.BLOCKED_DATA,eval(b,snapshot(),expiry(),history(),rights(),policy()).status());
    }
    @Test void quoteBookMustMatchEntireBrokerBookNoExtraOrMissingKeys(){
        assertEquals(Status.BLOCKED_DATA,eval(book(),snap(List.of(peQuote())),
                expiry(),history(),rights(),policy()).status());
        var extra=quote(CE_CAP,"CE","0.2","0.21",10,10,SRC,EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,eval(book(),snap(List.of(peQuote(),ceQuote(),extra)),
                expiry(),history(),rights(),policy()).status());
        assertEquals(Status.BLOCKED_DATA,eval(book(),snap(List.of(peQuote(),peQuote())),
                expiry(),history(),rights(),policy()).status());
    }
    @Test void quoteProviderCannotBeCrossBrokerEvenWithMatchedInstrument(){
        var ce=quote(CE,"CE","4","4.05",10,10,"ANGEL_ONE_SMARTAPI",EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,eval(book(),snap(List.of(peQuote(),ce)),
                expiry(),history(),rights(),policy()).status());
    }
    @Test void longSellUsesRealBidDepthAndShortBuyUsesRealAskDepth(){
        var pe=quote(PE,"PE","2.95","3",1,10,SRC,EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,eval(book(),snap(List.of(pe,ceQuote())),
                expiry(),history(),rights(),policy()).status());
        var ce=quote(CE,"CE","4","4.05",10,0,SRC,EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,eval(book(),snap(List.of(peQuote(),ce)),
                expiry(),history(),rights(),policy()).status());
    }
    @Test void futureKnownQuoteOrStaleQuoteCannotBeResearchData(){
        var ce=quote(CE,"CE","4","4.05",10,10,SRC,EX,NOW.plusSeconds(1));
        assertEquals(Status.BLOCKED_DATA,eval(book(),snap(List.of(peQuote(),ce)),
                expiry(),history(),rights(),policy()).status());
        ce=quote(CE,"CE","4","4.05",10,10,SRC,EX.minusSeconds(400),FIRST.minusSeconds(400));
        assertEquals(Status.BLOCKED_DATA,eval(book(),snap(List.of(peQuote(),ce)),
                expiry(),history(),rights(),policy()).status());
    }
    @Test void zeroOriginalGammaAndInvalidDeltaDirectionFailClosed(){
        var bad=new Quote(PE,SRC,n("2.95"),n("3"),n("-0.5"),
                BigDecimal.ZERO,n("26"),1000,10,10,EX,FIRST,true);
        assertEquals(Status.BLOCKED_DATA,eval(book(),snap(List.of(bad,ceQuote())),
                expiry(),history(),rights(),policy()).status());
        bad=new Quote(PE,SRC,n("2.95"),n("3"),n("0.5"),
                n("0.02"),n("26"),1000,10,10,EX,FIRST,true);
        assertEquals(Status.BLOCKED_DATA,eval(book(),snap(List.of(bad,ceQuote())),
                expiry(),history(),rights(),policy()).status());
    }
    @Test void originalIvOiAndBidAskAreRequired(){
        var bad=new Quote(PE,SRC,n("2.95"),n("3"),n("-0.5"),
                n("0.02"),null,1000,10,10,EX,FIRST,true);
        assertEquals(Status.BLOCKED_DATA,eval(book(),snap(List.of(bad,ceQuote())),
                expiry(),history(),rights(),policy()).status());
        bad=new Quote(PE,SRC,n("2.95"),n("3"),n("-0.5"),
                n("0.02"),n("26"),0,10,10,EX,FIRST,true);
        assertEquals(Status.BLOCKED_DATA,eval(book(),snap(List.of(bad,ceQuote())),
                expiry(),history(),rights(),policy()).status());
        bad=quote(PE,"PE","0.01","3",10,10,SRC,EX,FIRST);
        assertEquals(Status.BLOCKED_DATA,eval(book(),snap(List.of(bad,ceQuote())),
                expiry(),history(),rights(),policy()).status());
    }
    @Test void officialExpiryScheduleMustBeAuthenticatedNotHardcoded(){
        var e=new ExpiryEvidence("ANGEL_ONE_SMARTAPI",INDEX,EXPIRY,CUTOFF,SCHEDULE,true);
        assertEquals(Status.BLOCKED_DATA,eval(book(),snapshot(),e,history(),rights(),policy()).status());
        e=new ExpiryEvidence(SRC,INDEX,EXPIRY,CUTOFF,SCHEDULE,false);
        assertEquals(Status.BLOCKED_DATA,eval(book(),snapshot(),e,history(),rights(),policy()).status());
        e=expiry(Instant.parse("2026-11-21T10:00:00Z"));
        assertEquals(Status.BLOCKED_DATA,eval(book(),snapshot(),e,history(),rights(),policy()).status());
    }
    @Test void instrumentMustMatchActualExpiryAndOfficialIndex(){
        var otherExpiry=new Contract(PE,INDEX,"PE",n("100"),n("0.05"),
                LocalDate.of(2026,11,1),EXPIRY.plusDays(7),50,true);
        var b=new BrokerBook(true,BOOK,List.of(position(otherExpiry,2),position(call(),-1)));
        assertEquals(Status.BLOCKED_DATA,eval(b,snapshot(),expiry(),history(),rights(),policy()).status());
        var otherIndex=new Contract(PE,"NSE_INDEX|Other","PE",n("100"),n("0.05"),
                LocalDate.of(2026,11,1),EXPIRY,50,true);
        b=new BrokerBook(true,BOOK,List.of(position(otherIndex,2),position(call(),-1)));
        assertEquals(Status.BLOCKED_DATA,eval(b,snapshot(),expiry(),history(),rights(),policy()).status());
    }
    @Test void authenticTickAlignmentAndOfficialLotAreRequired(){
        var offTick=new Contract(PE,INDEX,"PE",n("100.01"),n("0.05"),
                LocalDate.of(2026,11,1),EXPIRY,50,true);
        var b=new BrokerBook(true,BOOK,List.of(position(offTick,2),position(call(),-1)));
        assertEquals(Status.BLOCKED_DATA,eval(b,snapshot(),expiry(),history(),rights(),policy()).status());
        var zeroLot=new Contract(PE,INDEX,"PE",n("100"),n("0.05"),
                LocalDate.of(2026,11,1),EXPIRY,0,true);
        b=new BrokerBook(true,BOOK,List.of(position(zeroLot,2),position(call(),-1)));
        assertEquals(Status.BLOCKED_DATA,eval(b,snapshot(),expiry(),history(),rights(),policy()).status());
    }
    @Test void historicalClosesNeedThirtyFiveCompletedOriginalSessions(){
        assertEquals(Status.BLOCKED_DATA,eval(book(),snapshot(),expiry(),
                history().subList(0,34),rights(),policy()).status());
    }
    @Test void futureKnownOrCrossProviderHistoryCannotBeUsed(){
        var bars=new ArrayList<>(history());
        var b=bars.get(5);
        bars.set(5,new CompletedClose(b.session(),SRC,b.close(),
                b.exchangeCloseAt(),NOW.plusSeconds(1),true));
        assertEquals(Status.BLOCKED_DATA,eval(book(),snapshot(),expiry(),
                bars,rights(),policy()).status());
        bars=new ArrayList<>(history());
        b=bars.get(5);
        bars.set(5,new CompletedClose(b.session(),"ANGEL_ONE_SMARTAPI",b.close(),
                b.exchangeCloseAt(),b.firstKnownAt(),true));
        assertEquals(Status.BLOCKED_DATA,eval(book(),snapshot(),expiry(),
                bars,rights(),policy()).status());
    }
    @Test void officialCutoffWindowIsSourceDrivenNotInferred(){
        var early=new Policy(180,30,15,240,500,10,300,10,10,10,10,n("5"),n("1000"));
        var d=eval(book(),snapshot(),expiry(),history(),rights(),early);
        assertEquals(Status.OUTSIDE_CUTOFF_WINDOW,d.status());
        assertEquals(300,d.minutesBeforeOfficialCutoff());
        neverOrders(d);
        d=eval(book(),snapshot(),expiry(Instant.parse("2026-11-20T05:10:00Z")),
                history(),rights(),policy());
        assertEquals(Status.OUTSIDE_CUTOFF_WINDOW,d.status());
        neverOrders(d);
    }
    @Test void aboveBudgetHedgeIsNoActionNotAnOrder(){
        var expensiveCe=quote(CE,"CE","50","50.05",10,10,SRC,EX,FIRST);
        var d=eval(book(),snap(List.of(peQuote(),expensiveCe)),expiry(),
                history(),rights(),policy());
        assertEquals(Status.NO_ACTION,d.status());
        assertTrue(d.hypotheticalNetCloseDebitInr().compareTo(n("1000"))>0);
        neverOrders(d);
    }
    @Test void extraLongHigherCallRemovesTheoreticalUnboundedUpsideLiability(){
        var b=new BrokerBook(true,BOOK,List.of(position(put(),2),position(call(),-1),
                position(capCall(),1)));
        var cap=quote(CE_CAP,"CE","0.2","0.21",10,10,SRC,EX,FIRST);
        var s=snap(List.of(peQuote(),ceQuote(),cap));
        var d=eval(b,s,expiry(),history(),rights(),policy());
        assertEquals(Status.RESEARCH_CLOSE_ALL,d.status());
        assertFalse(d.potentiallyUnlimitedUpsideShortCallLiability());
        assertEquals(3,d.authenticatedBookContracts());
        neverOrders(d);
    }
    @Test void neitherAllTrueFixtureNorResearchOutputGrantsTradingRights(){
        for (Decision d:List.of(run(),
                eval(book(),snapshot(),expiry(),history(),
                    new Rights(false,true,true,true,true,true),policy()),
                eval(book(),snapshot(),expiry(),history(),rights(),
                    new Policy(180,30,15,240,500,10,300,10,10,10,10,n("5"),n("1000")))))
            neverOrders(d);
    }
}
