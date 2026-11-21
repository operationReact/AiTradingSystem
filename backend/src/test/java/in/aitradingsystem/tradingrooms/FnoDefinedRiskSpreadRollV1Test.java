package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import in.aitradingsystem.tradingrooms.FnoDefinedRiskSpreadRollV1.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoDefinedRiskSpreadRollV1Test {
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant EX=Instant.parse("2026-11-20T04:59:20Z");
    private static final Instant KNOWN=Instant.parse("2026-11-20T04:59:24Z");
    private static final Instant BROKER=Instant.parse("2026-11-19T09:00:00Z");
    private static final String SRC="UPSTOX_ANALYTICS";
    private static final String INDEX="NSE_INDEX|Nifty 50";
    private static final String OL="NSE_FO|OLD_105_PE";
    private static final String OS="NSE_FO|OLD_95_PE";
    private static final String NL="NSE_FO|NEW_104_PE";
    private static final String NS="NSE_FO|NEW_94_PE";
    private static final LocalDate OLD=LocalDate.of(2026,11,27);
    private static final LocalDate NEW=LocalDate.of(2026,12,4);
    private static BigDecimal n(String s){return new BigDecimal(s);}
    private static Rights rights(){return new Rights(true,true,true,true,true,true);}
    private static Policy policy(){
        return new Policy(180,30,600,10,300,10,14,7,45,4,
                100,10,10,n("5"),n("500"),n("500"));
    }
    private static BrokerSpread held(int lots){
        return new BrokerSpread(OL,OS,lots,lots,n("200"),BROKER,true);
    }
    private static Contract contract(String key,String strike,LocalDate expiry,int lot){
        return new Contract(key,INDEX,"PE",n(strike),n("0.05"),
                LocalDate.of(2026,11,1),expiry,lot,true);
    }
    private static Contract oldLong(){return contract(OL,"105",OLD,50);}
    private static Contract oldShort(){return contract(OS,"95",OLD,50);}
    private static Contract newLong(){return contract(NL,"104",NEW,50);}
    private static Contract newShort(){return contract(NS,"94",NEW,50);}
    private static Quote quote(String key,String bid,String ask,long bidDepth,
                               long askDepth,String provider,Instant exchange,Instant firstKnown){
        return new Quote(provider,key,n(bid),n(ask),n("-0.4"),n("25"),
                100,bidDepth,askDepth,exchange,firstKnown,true);
    }
    private static Quote oldLongQuote(){return quote(OL,"8","8.1",10,10,SRC,EX,KNOWN);}
    private static Quote oldShortQuote(){return quote(OS,"2","2.1",10,10,SRC,EX,KNOWN);}
    private static Quote newLongQuote(){return quote(NL,"6.9","7",10,10,SRC,EX,KNOWN);}
    private static Quote newShortQuote(){return quote(NS,"2","2.1",10,10,SRC,EX,KNOWN);}
    private static Snapshot snap(Quote oldL,Quote oldS,Quote newL,Quote newS){
        return new Snapshot(SRC,INDEX,n("100"),oldL,oldS,newL,newS,EX,KNOWN,true);
    }
    private static Snapshot snapshot(){
        return snap(oldLongQuote(),oldShortQuote(),newLongQuote(),newShortQuote());
    }
    /** Fictional reproducible source-shaped unit fixtures, NOT actual NSE data. */
    private static List<CompletedClose> history(boolean strictlyRising){
        var days=new ArrayList<LocalDate>();
        LocalDate day=LocalDate.of(2026,11,19);
        while(days.size()<35){
            if(day.getDayOfWeek().getValue()<=5)days.add(0,day);
            day=day.minusDays(1);
        }
        var series=new ArrayList<CompletedClose>();
        for(int i=0;i<days.size();i++){
            LocalDate d=days.get(i);
            Instant close=d.atTime(LocalTime.of(15,30))
                    .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            BigDecimal px=strictlyRising?n(Integer.toString(100+i)) :
                    (i%2==0?n("100"):n("102"));
            series.add(new CompletedClose(d,SRC,px,close,close.plusSeconds(60),true));
        }
        return series;
    }
    private static List<CompletedClose> history(){return history(false);}
    private static Decision evaluate(BrokerSpread b,Contract ol,Contract os,
                                     Contract nl,Contract ns,Snapshot s,
                                     List<CompletedClose> bars,Rights r,Policy p){
        return FnoDefinedRiskSpreadRollV1.evaluate(b,ol,os,nl,ns,s,bars,r,p,NOW);
    }
    private static Decision run(){
        return evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                snapshot(),history(),rights(),policy());
    }
    private static void neverExecutable(Decision d){
        assertEquals("strategy.STR-208",d.strategyId());
        assertTrue(d.expiryScenarioOnly());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.paperTradeAuthorized());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test void originalPositionsFourSidesAndStrictRiskAreHypotheticalOnly(){
        Decision d=run();
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(1,d.verifiedExistingLots());
        assertEquals(1,d.hypotheticalRollLots());
        assertEquals(OLD,d.oldExpiry());
        assertEquals(NEW,d.newExpiry());
        assertTrue(d.worstCompletedIndexDeclineBps().compareTo(n("190"))>0);
        assertEquals(0,n("294.495").compareTo(d.modeledOldCloseCreditInr()));
        assertEquals(0,n("250.45").compareTo(d.modeledNewOpenDebitInr()));
        assertEquals(0,n("-39.045").compareTo(d.modeledNetRollDebitInr()));
        assertEquals(0,n("255.45").compareTo(d.modeledNewVerticalMaximumLossInr()));
        assertEquals(0,n("244.55").compareTo(d.modeledNewVerticalMaximumGainInr()));
        assertEquals(0,n("160.955").compareTo(d.modeledWholeLifecycleNetDebitInr()));
        neverExecutable(d);
    }
    @Test void allSixRightsIndependentlyRequired(){
        var r=new Rights(true,true,true,true,false,true);
        Decision d=evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                snapshot(),history(),r,policy());
        assertEquals(Status.BLOCKED_RIGHTS,d.status());
        neverExecutable(d);
    }
    @Test void unverifiedOrShortOrUnbalancedOldBrokerLegsBlock(){
        for(var p:List.of(
                new BrokerSpread(OL,OS,1,1,n("200"),BROKER,false),
                new BrokerSpread(OL,OS,1,0,n("200"),BROKER,true),
                new BrokerSpread(OL,OS,-1,-1,n("200"),BROKER,true),
                new BrokerSpread(OL,OS,1,2,n("200"),BROKER,true))){
            assertEquals(Status.BLOCKED_DATA,
                    evaluate(p,oldLong(),oldShort(),newLong(),newShort(),
                            snapshot(),history(),rights(),policy()).status());
        }
    }
    @Test void existingBrokerEntryBasisMustBeActualPositiveAndBounded(){
        for(String cost:List.of("0","600")){
            var b=new BrokerSpread(OL,OS,1,1,n(cost),BROKER,true);
            assertEquals(Status.BLOCKED_DATA,
                    evaluate(b,oldLong(),oldShort(),newLong(),newShort(),
                            snapshot(),history(),rights(),policy()).status());
        }
    }
    @Test void originalBrokerEvidenceMustPrecedeSnapshot(){
        var futureKnown=new BrokerSpread(OL,OS,1,1,n("200"),NOW,true);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(futureKnown,oldLong(),oldShort(),newLong(),newShort(),
                        snapshot(),history(),rights(),policy()).status());
    }
    @Test void bothBrokerKeysMustMatchActualOldSpreadLegs(){
        var broken=new BrokerSpread(NL,OS,1,1,n("200"),BROKER,true);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(broken,oldLong(),oldShort(),newLong(),newShort(),
                        snapshot(),history(),rights(),policy()).status());
    }
    @Test void oldExpiryMustBeNearAndNewExpiryStrictlyLater(){
        var wrong=contract(NL,"104",OLD,50);
        var shortWrong=contract(NS,"94",OLD,50);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),wrong,shortWrong,
                        snapshot(),history(),rights(),policy()).status());
        wrong=contract(NL,"104",OLD.plusDays(1),50);
        shortWrong=contract(NS,"94",OLD.plusDays(1),50);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),wrong,shortWrong,
                        snapshot(),history(),rights(),policy()).status());
    }
    @Test void futureListedDifferentLotOrUnderlyingCannotBeRolled(){
        var wrong=contract(NL,"104",NEW,75);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),wrong,newShort(),
                        snapshot(),history(),rights(),policy()).status());
        wrong=new Contract(NL,"NSE_INDEX|Other","PE",n("104"),n("0.05"),
                LocalDate.of(2026,11,1),NEW,50,true);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),wrong,newShort(),
                        snapshot(),history(),rights(),policy()).status());
    }
    @Test void shortPutCannotBeAboveLongPut(){
        var wrong=contract(NS,"105",NEW,50);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),newLong(),wrong,
                        snapshot(),history(),rights(),policy()).status());
    }
    @Test void duplicateOfficialOptionKeysFailClosedNotThrow(){
        var wrong=contract(OL,"104",NEW,50);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),wrong,newShort(),
                        snapshot(),history(),rights(),policy()).status());
    }
    @Test void everyLegRequiresCorrectBidOrAskDepthForWholeSize(){
        // Closing old long uses BID. Closing old short uses ASK.
        var badOldL=quote(OL,"8","8.1",1,10,SRC,EX,KNOWN);
        var badOldS=quote(OS,"2","2.1",10,1,SRC,EX,KNOWN);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(2),oldLong(),oldShort(),newLong(),newShort(),
                        snap(badOldL,oldShortQuote(),newLongQuote(),newShortQuote()),
                        history(),rights(),policy()).status());
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(2),oldLong(),oldShort(),newLong(),newShort(),
                        snap(oldLongQuote(),badOldS,newLongQuote(),newShortQuote()),
                        history(),rights(),policy()).status());
        // Opening new long uses ASK. Opening new short uses BID.
        var badNewL=quote(NL,"6.9","7",10,1,SRC,EX,KNOWN);
        var badNewS=quote(NS,"2","2.1",1,10,SRC,EX,KNOWN);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(2),oldLong(),oldShort(),newLong(),newShort(),
                        snap(oldLongQuote(),oldShortQuote(),badNewL,newShortQuote()),
                        history(),rights(),policy()).status());
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(2),oldLong(),oldShort(),newLong(),newShort(),
                        snap(oldLongQuote(),oldShortQuote(),newLongQuote(),badNewS),
                        history(),rights(),policy()).status());
    }
    @Test void originalOptionQuoteProviderMixingMustFail(){
        var q=quote(NL,"6.9","7",10,10,"ANGEL_ONE_SMARTAPI",EX,KNOWN);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                        snap(oldLongQuote(),oldShortQuote(),q,newShortQuote()),
                        history(),rights(),policy()).status());
    }
    @Test void originallyFutureKnownOrStaleGreeksCannotBeUsed(){
        var q=quote(NL,"6.9","7",10,10,SRC,EX,NOW);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                        snap(oldLongQuote(),oldShortQuote(),q,newShortQuote()),
                        history(),rights(),policy()).status());
        q=quote(NL,"6.9","7",10,10,SRC,EX.minusSeconds(300),KNOWN.minusSeconds(300));
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                        snap(oldLongQuote(),oldShortQuote(),q,newShortQuote()),
                        history(),rights(),policy()).status());
    }
    @Test void genuinePutDeltaSignAndOriginalIvAreNecessary(){
        var q=new Quote(SRC,NL,n("6.9"),n("7"),n("0.4"),n("25"),100,
                10,10,EX,KNOWN,true);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                        snap(oldLongQuote(),oldShortQuote(),q,newShortQuote()),
                        history(),rights(),policy()).status());
        q=new Quote(SRC,NL,n("6.9"),n("7"),n("-0.4"),null,100,
                10,10,EX,KNOWN,true);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                        snap(oldLongQuote(),oldShortQuote(),q,newShortQuote()),
                        history(),rights(),policy()).status());
    }
    @Test void quoteSpreadAndOpenInterestHaveHardThresholds(){
        var q=quote(NL,"1","7",10,10,SRC,EX,KNOWN);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                        snap(oldLongQuote(),oldShortQuote(),q,newShortQuote()),
                        history(),rights(),policy()).status());
        q=new Quote(SRC,NL,n("6.9"),n("7"),n("-0.4"),n("25"),0,
                10,10,EX,KNOWN,true);
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                        snap(oldLongQuote(),oldShortQuote(),q,newShortQuote()),
                        history(),rights(),policy()).status());
    }
    @Test void requiresThirtyFiveOriginalCompletedNiftySessions(){
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                        snapshot(),history().subList(0,34),rights(),policy()).status());
    }
    @Test void cannotMixHistoricalProvidersOrFutureKnownCloses(){
        var bars=new ArrayList<>(history());
        var b=bars.get(5);
        bars.set(5,new CompletedClose(b.session(),"ANGEL_ONE_SMARTAPI",
                b.indexClose(),b.exchangeCloseAt(),b.firstKnownAt(),true));
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                        snapshot(),bars,rights(),policy()).status());
        bars=new ArrayList<>(history());
        b=bars.get(5);
        bars.set(5,new CompletedClose(b.session(),b.provider(),
                b.indexClose(),b.exchangeCloseAt(),NOW.plusSeconds(1),true));
        assertEquals(Status.BLOCKED_DATA,
                evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                        snapshot(),bars,rights(),policy()).status());
    }
    @Test void noSourceObservedDownsideMeansNoResearchRoll(){
        Decision d=evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                snapshot(),history(true),rights(),policy());
        assertEquals(Status.NO_ROLL,d.status());
        assertEquals(0,d.hypotheticalRollLots());
        neverExecutable(d);
    }
    @Test void higherModeledRollDebitCanFailWithoutRiskClaims(){
        var strict=new Policy(180,30,600,10,300,10,14,7,45,4,
                100,10,10,n("5"),n("1"),n("250"));
        var q=quote(OL,"1.04","1.1",10,10,SRC,EX,KNOWN);
        var s=snap(q,oldShortQuote(),newLongQuote(),newShortQuote());
        Decision d=evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                s,history(),rights(),strict);
        assertEquals(Status.NO_ROLL,d.status());
        assertEquals(0,d.hypotheticalRollLots());
        neverExecutable(d);
    }
    @Test void tooExpensiveNewDebitCannotBeClaimedDefinedRiskCreditRoll(){
        var q=quote(NL,"13.9","14",10,10,SRC,EX,KNOWN);
        var s=snap(oldLongQuote(),oldShortQuote(),q,newShortQuote());
        Decision d=evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                s,history(),rights(),policy());
        assertEquals(Status.NO_ROLL,d.status());
        assertEquals("ROLL_NEW_VERTICAL_NOT_DEFINED_DEBIT",d.reasonCode());
        neverExecutable(d);
    }
    @Test void twoActualBrokerLotsProduceTwoHypotheticalStructures(){
        var b=new BrokerSpread(OL,OS,2,2,n("400"),BROKER,true);
        var d=evaluate(b,oldLong(),oldShort(),newLong(),newShort(),
                snapshot(),history(),rights(),policy());
        assertEquals(Status.NO_ROLL,d.status()); // new risk 505.9 > 500 declared
        assertEquals(0,d.hypotheticalRollLots());
        neverExecutable(d);
        var wider=new Policy(180,30,600,10,300,10,14,7,45,4,
                100,10,10,n("5"),n("500"),n("1000"));
        d=evaluate(b,oldLong(),oldShort(),newLong(),newShort(),
                snapshot(),history(),rights(),wider);
        assertEquals(Status.RESEARCH_CANDIDATE,d.status());
        assertEquals(2,d.hypotheticalRollLots());
        neverExecutable(d);
    }
    @Test void sourceRightsDoNotConferAnyPaperOrLiveExecution(){
        var outcomes=List.of(run(),evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                        snapshot(),history(),new Rights(false,true,true,true,true,true),policy()),
                evaluate(held(1),oldLong(),oldShort(),newLong(),newShort(),
                        snapshot(),history(true),rights(),policy()));
        outcomes.forEach(FnoDefinedRiskSpreadRollV1Test::neverExecutable);
    }
}
