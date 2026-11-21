package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import in.aitradingsystem.tradingrooms.FnoIvRankMeanReversionV1.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoIvRankMeanReversionV1Test {
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant TICK=NOW.minusSeconds(40);
    private static final String SOURCE="UPSTOX_ANALYTICS";
    private static final String INDEX=FnoLongOptionCrossoverV1.UNDERLYING;
    private static final String METHOD=FnoIvRankMeanReversionV1.SOURCE_IV_METHOD;
    private static final Rights RIGHTS=new Rights(true,true,true,true);
    private static final Assumptions A=new Assumptions(200,450,180,300,
        bd("8"),80,20,5,bd("2"));

    static BigDecimal bd(String x){return new BigDecimal(x);}

    static List<LocalDate> sessions(){
        var dates=new ArrayList<LocalDate>();
        var day=LocalDate.of(2026,11,19);
        while(dates.size()<252) {
            if(day.getDayOfWeek().getValue()<=5)dates.add(0,day);
            day=day.minusDays(1);
        }
        return dates;
    }
    static List<CompletedCandle> candles(){
        var result=new ArrayList<CompletedCandle>();
        for(var day:sessions()){
            var closeAt=day.atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            result.add(new CompletedCandle(day,SOURCE,bd("100"),bd("102"),
                bd("98"),bd("100"),1000,closeAt,closeAt.plusSeconds(60),true));
        }
        return result;
    }
    static List<DailyIv> ivHistory(){
        var days=sessions();
        var result=new ArrayList<DailyIv>();
        String[] lastFive={"40","42","43","41","39"};
        for(int i=0;i<days.size();i++){
            var day=days.get(i);
            var closeAt=day.atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            String v=i==0?"12":i==1?"44":i>=days.size()-5
                ?lastFive[i-(days.size()-5)]:"25";
            result.add(new DailyIv(day,SOURCE,bd(v),30,METHOD,
                closeAt.plusSeconds(90),true));
        }
        return result;
    }
    static CurrentIv now(String value){
        return new CurrentIv(SOURCE,INDEX,bd(value),30,METHOD,
            TICK,TICK.plusSeconds(10),true);
    }
    static Decision evaluate(List<CompletedCandle> bars,List<DailyIv> history,
                             CurrentIv current,Rights rights,Assumptions a) {
        return FnoIvRankMeanReversionV1.evaluate(bars,history,current,rights,a,NOW);
    }
    static Decision run(){
        return evaluate(candles(),ivHistory(),now("38"),RIGHTS,A);
    }
    static void check(Decision d,Status status,String reason){
        assertEquals(status,d.status());
        assertEquals(reason,d.reasonCode());
        assertEquals("strategy.STR-201",d.strategyId());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test void highIvRankRequiresObservedPullbackNotMerelyHighVolatility(){
        var result=run();
        check(result,Status.RESEARCH_CANDIDATE,"IV_RANK_HIGH_IV_PULLBACK_RESEARCH_ONLY");
        assertEquals(ResearchRegime.HIGH_IV_PULLBACK,result.regime());
        assertEquals(252,result.historySessions());
        assertEquals(0,result.ivRankPercent().compareTo(bd("81.2500")));
        assertEquals(0,result.fiveDayExtremumIvPercent().compareTo(bd("43")));
        assertEquals(0,result.reversalIvPoints().compareTo(bd("5")));
        assertEquals(0,result.historicalMaxIvPercent().compareTo(bd("44")));
        assertEquals(0,result.historicalMinIvPercent().compareTo(bd("12")));
    }
    @Test void lowIvRankRequiresOriginalRecentLowAndActualRebound(){
        var history=ivHistory();
        String[] lows={"12","14","15","16","17"};
        for(int i=0;i<5;i++) {
            var p=history.size()-5+i;
            var old=history.get(p);
            history.set(p,new DailyIv(old.session(),old.provider(),bd(lows[i]),
                30,METHOD,old.firstKnownAt(),true));
        }
        var d=evaluate(candles(),history,now("17"),RIGHTS,A);
        check(d,Status.RESEARCH_CANDIDATE,"IV_RANK_LOW_IV_REBOUND_RESEARCH_ONLY");
        assertEquals(ResearchRegime.LOW_IV_REBOUND,d.regime());
        assertEquals(0,d.ivRankPercent().compareTo(bd("15.6250")));
        assertEquals(0,d.reversalIvPoints().compareTo(bd("5")));
    }
    @Test void highIvRankWithoutPullbackIsNotCalledMeanReversion(){
        check(evaluate(candles(),ivHistory(),now("44"),RIGHTS,A),
            Status.NO_SIGNAL,"IV_RANK_NO_VERIFIED_REVERSAL");
    }
    @Test void lowIvRankWithoutLowPriceReboundIsNotCalledMeanReversion(){
        check(evaluate(candles(),ivHistory(),now("12"),RIGHTS,A),
            Status.NO_SIGNAL,"IV_RANK_NO_VERIFIED_REVERSAL");
    }
    @Test void allFourLicensedDataRightsAreMandatory(){
        for(var rights:List.of(new Rights(false,true,true,true),
                new Rights(true,false,true,true),
                new Rights(true,true,false,true),
                new Rights(true,true,true,false))) {
            check(evaluate(candles(),ivHistory(),now("38"),rights,A),
                Status.BLOCKED_RIGHTS,"IV_RANK_SOURCE_RIGHTS_UNVERIFIED");
        }
    }
    @Test void historicalSeriesMustBeAlignedBySessionAndProvider(){
        var history=ivHistory();var prior=history.get(1);
        history.set(1,new DailyIv(prior.session().plusDays(1),prior.provider(),
            prior.atmIvPercent(),30,METHOD,prior.firstKnownAt(),true));
        check(evaluate(candles(),history,now("38"),RIGHTS,A),
            Status.BLOCKED_DATA,"IV_RANK_ORIGINAL_HISTORY_INVALID");
        history=ivHistory();prior=history.get(1);
        history.set(1,new DailyIv(prior.session(),"ANGEL_ONE_SMARTAPI",
            prior.atmIvPercent(),30,METHOD,prior.firstKnownAt(),true));
        check(evaluate(candles(),history,now("38"),RIGHTS,A),
            Status.BLOCKED_DATA,"IV_RANK_ORIGINAL_HISTORY_INVALID");
    }
    @Test void historicalIvMustBeGenuineThirtyDayAtmAnnualizedPercent(){
        var history=ivHistory();var original=history.get(1);
        for(var invalid:List.of(
                new DailyIv(original.session(),SOURCE,bd("0"),30,METHOD,
                    original.firstKnownAt(),true),
                new DailyIv(original.session(),SOURCE,bd("301"),30,METHOD,
                    original.firstKnownAt(),true),
                new DailyIv(original.session(),SOURCE,bd("44"),14,METHOD,
                    original.firstKnownAt(),true),
                new DailyIv(original.session(),SOURCE,bd("44"),30,"LTP_SYNTHETIC",
                    original.firstKnownAt(),true),
                new DailyIv(original.session(),SOURCE,bd("44"),30,METHOD,
                    original.firstKnownAt(),false))) {
            var altered=ivHistory();
            altered.set(1,invalid);
            check(evaluate(candles(),altered,now("38"),RIGHTS,A),
                Status.BLOCKED_DATA,"IV_RANK_ORIGINAL_HISTORY_INVALID");
        }
    }
    @Test void historicalFirstKnownAfterAsOfAndDuplicateDatesBlock(){
        var historical=ivHistory();
        var original=historical.getLast();
        historical.set(historical.size()-1,new DailyIv(original.session(),SOURCE,
            original.atmIvPercent(),30,METHOD,NOW.plusSeconds(1),true));
        check(evaluate(candles(),historical,now("38"),RIGHTS,A),
            Status.BLOCKED_DATA,"IV_RANK_ORIGINAL_HISTORY_INVALID");
        var bars=candles();bars.set(200,bars.get(199));
        check(evaluate(bars,ivHistory(),now("38"),RIGHTS,A),
            Status.BLOCKED_DATA,"IV_RANK_ORIGINAL_HISTORY_INVALID");
    }
    @Test void minimumSampleRequirementAndHistoryWindowCannotBeFaked(){
        check(evaluate(candles().subList(0,150),ivHistory().subList(0,150),
            now("38"),RIGHTS,A),
            Status.BLOCKED_DATA,"IV_RANK_ORIGINAL_HISTORY_INVALID");
        var stricter=new Assumptions(300,450,180,300,bd("8"),80,20,5,bd("2"));
        check(evaluate(candles(),ivHistory(),now("38"),RIGHTS,stricter),
            Status.BLOCKED_DATA,"IV_RANK_ORIGINAL_HISTORY_INVALID");
    }
    @Test void currentIvMustBeOriginalAndNotStaleOrForwardKnown(){
        for(var bad:List.of(
            new CurrentIv(SOURCE,INDEX,bd("38"),30,METHOD,NOW.minusSeconds(250),
                NOW.minusSeconds(240),true),
            new CurrentIv(SOURCE,INDEX,bd("38"),30,METHOD,TICK,NOW.plusSeconds(1),true),
            new CurrentIv(SOURCE,INDEX,bd("38"),30,METHOD,TICK,TICK.plusSeconds(10),false),
            new CurrentIv(SOURCE,INDEX,bd("38"),20,METHOD,TICK,TICK.plusSeconds(10),true),
            new CurrentIv(SOURCE,INDEX,bd("38"),30,"SYNTHETIC",
                TICK,TICK.plusSeconds(10),true),
            new CurrentIv("ANGEL_ONE_SMARTAPI",INDEX,bd("38"),30,METHOD,
                TICK,TICK.plusSeconds(10),true),
            new CurrentIv(SOURCE,INDEX,bd("0"),30,METHOD,TICK,
                TICK.plusSeconds(10),true))) {
            check(evaluate(candles(),ivHistory(),bad,RIGHTS,A),
                Status.BLOCKED_DATA,"IV_RANK_CURRENT_SOURCE_IV_INVALID");
        }
    }
    @Test void historicalRangeTooNarrowIsNotAReversionEvent(){
        var history=ivHistory();
        for(int i=0;i<history.size();i++){
            var p=history.get(i);
            history.set(i,new DailyIv(p.session(),SOURCE,bd("24.5"),30,METHOD,
                p.firstKnownAt(),true));
        }
        check(evaluate(candles(),history,now("24.8"),RIGHTS,A),
            Status.NO_SIGNAL,"IV_RANK_HISTORICAL_RANGE_TOO_NARROW");
    }
    @Test void breachedIndexOhlcAndInvalidAssumptionsFailClosed(){
        var bars=candles();
        var original=bars.get(0);
        bars.set(0,new CompletedCandle(original.session(),SOURCE,bd("100"),
            bd("99"),bd("98"),bd("100"),1000,
            original.exchangeCloseAt(),original.firstKnownAt(),true));
        check(evaluate(bars,ivHistory(),now("38"),RIGHTS,A),
            Status.BLOCKED_DATA,"IV_RANK_ORIGINAL_HISTORY_INVALID");
        var invalid=new Assumptions(200,450,180,300,bd("8"),20,80,5,bd("2"));
        check(evaluate(candles(),ivHistory(),now("38"),RIGHTS,invalid),
            Status.BLOCKED_DATA,"IV_RANK_ASSUMPTIONS_INVALID");
    }
    @Test void historicallyUnseenHighIsClampedToHundredButNeedsActualReversal(){
        var d=evaluate(candles(),ivHistory(),now("49"),RIGHTS,A);
        check(d,Status.NO_SIGNAL,"IV_RANK_NO_VERIFIED_REVERSAL");
        assertEquals(0,d.ivRankPercent().compareTo(bd("100")));
    }
    @Test void repeatabilityAndNoOrderRemainTrueForEveryState(){
        assertEquals(run(),run());
        check(run(),Status.RESEARCH_CANDIDATE,"IV_RANK_HIGH_IV_PULLBACK_RESEARCH_ONLY");
        assertEquals(ResearchRegime.NONE,
            evaluate(candles(),ivHistory(),now("25"),RIGHTS,A).regime());
    }
}
