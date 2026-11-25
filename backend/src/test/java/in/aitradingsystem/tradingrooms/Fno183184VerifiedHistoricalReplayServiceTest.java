package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyDebitSpreadSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Synthetic source mocks test actual unchanged debit spread evaluators. */
class Fno183184VerifiedHistoricalReplayServiceTest {
    private static final Instant AS_OF=Instant.parse("2026-11-20T05:00:00Z");
    private static final Clock NOW=Clock.fixed(
        Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC);
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final String INDEX="NSE_INDEX|Nifty 50";
    private static final String PROVIDER="UPSTOX_ANALYTICS";
    private static final String BULL="strategy.STR-183";
    private static final String BEAR="strategy.STR-184";
    private static final String CE_LOW="NSE_FO|NIFTY_20261127_125_CE";
    private static final String CE_HIGH="NSE_FO|NIFTY_20261127_130_CE";
    private static final String PE_LOW="NSE_FO|NIFTY_20261127_70_PE";
    private static final String PE_HIGH="NSE_FO|NIFTY_20261127_75_PE";
    private final FnoVerifiedNiftyLongOptionSourceStore daily=
        mock(FnoVerifiedNiftyLongOptionSourceStore.class);
    private final FnoVerifiedNiftyDebitSpreadSourceStore spread=
        mock(FnoVerifiedNiftyDebitSpreadSourceStore.class);
    private final MarketSessionCalendar calendar=mock(MarketSessionCalendar.class);

    private Fno183184VerifiedHistoricalReplayService service(boolean a,boolean b,boolean c,boolean d){
        return new Fno183184VerifiedHistoricalReplayService(
            daily,spread,calendar,NOW,a,b,c,d);
    }

    private static List<FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose> closes(String last){
        var dates=new ArrayList<LocalDate>();
        var day=LocalDate.of(2026,11,19);
        while(dates.size()<35){
            if(day.getDayOfWeek().getValue()<=5)dates.add(0,day);
            day=day.minusDays(1);
        }
        var out=new ArrayList<FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose>();
        for(int i=0;i<dates.size();i++){
            Instant close=dates.get(i).atTime(15,30).atZone(IST).toInstant();
            out.add(new FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose(
                PROVIDER,INDEX,dates.get(i),
                new BigDecimal(i==34?last:"100"),close.plusSeconds(60),close));
        }
        return List.copyOf(out);
    }

    private static FnoVerifiedNiftyDebitSpreadSourceStore.Leg leg(
            String key,String kind,String strike,String bid,String ask){
        return new FnoVerifiedNiftyDebitSpreadSourceStore.Leg(
            key,kind,LocalDate.of(2026,11,1),LocalDate.of(2026,11,27),
            50,new BigDecimal(strike),new BigDecimal("0.05"),
            new BigDecimal(bid),new BigDecimal(ask),
            10,10,1000,AS_OF.minusSeconds(40),AS_OF.minusSeconds(30));
    }

    private static FnoVerifiedNiftyDebitSpreadSourceStore.Proof proof(boolean bull){
        var lower=bull?leg(CE_LOW,"CE","125","5","5.10")
            :leg(PE_LOW,"PE","70","2.50","2.55");
        var higher=bull?leg(CE_HIGH,"CE","130","2.50","2.55")
            :leg(PE_HIGH,"PE","75","5","5.10");
        var tick=new FnoVerifiedNiftyDebitSpreadSourceStore.IndexTick(
            new BigDecimal(bull?"125":"75"),AS_OF.minusSeconds(40),
            AS_OF.minusSeconds(30));
        var risk=new FnoVerifiedNiftyDebitSpreadSourceStore.Risk(
            50,50,new BigDecimal("10"),300,2,50,5000,
            new BigDecimal("10000"),3000,3000);
        return new FnoVerifiedNiftyDebitSpreadSourceStore.Proof(
            PROVIDER,bull?"BULL_CALL":"BEAR_PUT",INDEX,
            lower,higher,tick,risk,"a".repeat(64));
    }

    private void eligible(String last){
        when(calendar.isInsideCashSession(AS_OF)).thenReturn(true);
        when(calendar.isTradingDay(any(LocalDate.class))).thenAnswer(inv ->
            ((LocalDate)inv.getArgument(0)).getDayOfWeek().getValue()<=5);
        when(daily.verifiedRecentCloses(AS_OF)).thenReturn(closes(last));
        when(spread.oneCurrentSpread("BULL_CALL",CE_LOW,CE_HIGH,AS_OF))
            .thenReturn(Optional.of(proof(true)));
        when(spread.oneCurrentSpread("BEAR_PUT",PE_LOW,PE_HIGH,AS_OF))
            .thenReturn(Optional.of(proof(false)));
    }

    @Test void genuineTwoLegBullSpreadActuallyRunsOriginalEvaluatorResearchOnly(){
        eligible("125");
        var report=service(true,true,true,true).inspect(
            BULL,CE_LOW,CE_HIGH,AS_OF);
        assertEquals("ORIGINAL_SOURCE_RESEARCH_EVALUATOR_RAN_NOT_E2E",report.state());
        assertEquals(35,report.verifiedDailySourceSessions());
        assertEquals(2,report.verifiedOptionContracts());
        assertEquals("BULL_CALL_SPREAD_RESEARCH_SETUP_ONLY",report.evaluatorReasonCode());
        assertFalse(report.independentHistoricalE2E());
        assertFalse(report.paperLedgerE2E());
        assertFalse(report.liveBrokerE2E());
        assertFalse(report.brokerOrdersPlaced());
        assertFalse(report.toString().contains(CE_LOW));
        verify(spread).oneCurrentSpread("BULL_CALL",CE_LOW,CE_HIGH,AS_OF);
        verify(spread,never()).oneCurrentSpread(eq("BEAR_PUT"),any(),any(),any());
    }

    @Test void genuineTwoLegBearSpreadUsesActualIndependentPutEvaluator(){
        eligible("75");
        var report=service(true,true,true,true).inspect(
            BEAR,PE_LOW,PE_HIGH,AS_OF);
        assertEquals("ORIGINAL_SOURCE_RESEARCH_EVALUATOR_RAN_NOT_E2E",report.state());
        assertEquals(35,report.verifiedDailySourceSessions());
        assertEquals(2,report.verifiedOptionContracts());
        assertTrue(report.evaluatorReasonCode().startsWith("BEAR_PUT"));
        assertFalse(report.independentHistoricalE2E());
        verify(spread).oneCurrentSpread("BEAR_PUT",PE_LOW,PE_HIGH,AS_OF);
        verify(spread,never()).oneCurrentSpread(eq("BULL_CALL"),any(),any(),any());
    }

    @Test void oneMissingSourceRightsDomainPreventsAllQueries(){
        for(int missing=0;missing<4;missing++){
            var f=new boolean[]{true,true,true,true};f[missing]=false;
            var result=service(f[0],f[1],f[2],f[3]).inspect(
                BULL,CE_LOW,CE_HIGH,AS_OF);
            assertEquals("ORIGINAL_DEBIT_SPREAD_SOURCE_RIGHTS_NOT_VERIFIED",
                result.evaluatorReasonCode());
            assertEquals(0,result.verifiedOptionContracts());
        }
        verifyNoInteractions(daily,spread,calendar);
    }

    @Test void invalidCrossContractOrFutureAsOfNeverTouchesOriginalSources(){
        var svc=service(true,true,true,true);
        for (var args : List.of(
                List.of("NSE_EQ|BAD",CE_HIGH),
                List.of(CE_LOW,CE_LOW),
                List.of(CE_LOW,"NSE_FO|../INJECT"))) {
            var r=svc.inspect(BULL,args.get(0),args.get(1),AS_OF);
            assertEquals("ORIGINAL_DEBIT_SPREAD_ASOF_SCOPE_INVALID",
                r.evaluatorReasonCode());
        }
        assertEquals("ORIGINAL_DEBIT_SPREAD_ASOF_SCOPE_INVALID",
            svc.inspect(BULL,CE_LOW,CE_HIGH,NOW.instant().plusSeconds(1))
                .evaluatorReasonCode());
        assertEquals("HISTORICAL_DEBIT_SPREAD_STRATEGY_UNSUPPORTED",
            svc.inspect("strategy.STR-185",CE_LOW,CE_HIGH,AS_OF).evaluatorReasonCode());
        verifyNoInteractions(daily,spread);
    }

    @Test void absentOrMixedSourceCannotProduceResearchCandidate(){
        eligible("125");
        when(spread.oneCurrentSpread("BULL_CALL",CE_LOW,CE_HIGH,AS_OF))
            .thenReturn(Optional.empty());
        var noSource=service(true,true,true,true).inspect(
            BULL,CE_LOW,CE_HIGH,AS_OF);
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",noSource.state());
        assertEquals(0,noSource.verifiedOptionContracts());
        assertEquals("DEBIT_SPREAD_TWO_LEG_QUOTES_COSTS_OR_DEPTH_PROOF_MISSING",
            noSource.evaluatorReasonCode());
        when(daily.verifiedRecentCloses(AS_OF)).thenReturn(closes("125").subList(0,34));
        var noHistory=service(true,true,true,true).inspect(
            BULL,CE_LOW,CE_HIGH,AS_OF);
        assertEquals("DEBIT_SPREAD_35_COMPLETED_CLOSES_INVALID",
            noHistory.evaluatorReasonCode());
        assertEquals(0,noHistory.verifiedDailySourceSessions());
    }

    @Test void sensitiveSourceErrorsAreRedactedAndNeverAuthorizeTrades(){
        eligible("125");
        when(daily.verifiedRecentCloses(AS_OF))
            .thenThrow(new IllegalStateException("provider token secret"));
        var result=service(true,true,true,true).inspect(
            BULL,CE_LOW,CE_HIGH,AS_OF);
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",result.state());
        assertFalse(result.toString().contains("token"));
        assertFalse(result.brokerOrdersPlaced());
    }
}
