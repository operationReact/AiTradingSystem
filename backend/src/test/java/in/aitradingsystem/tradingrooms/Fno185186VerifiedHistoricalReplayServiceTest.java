package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyCreditSpreadSourceStore;
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

/** Fictional fixtures test original evaluator routing and historical margin gates. */
class Fno185186VerifiedHistoricalReplayServiceTest {
    private static final Instant AS_OF=Instant.parse("2026-11-20T05:00:00Z");
    private static final Clock NOW=Clock.fixed(
        Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC);
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final String ACCOUNT="private_margin_account";
    private static final String INDEX="NSE_INDEX|Nifty 50";
    private static final String PROVIDER="UPSTOX_ANALYTICS";
    private static final String BULL="strategy.STR-185";
    private static final String BEAR="strategy.STR-186";
    private static final String PE_LOW="NSE_FO|NIFTY_20261127_120_PE";
    private static final String PE_HIGH="NSE_FO|NIFTY_20261127_125_PE";
    private static final String CE_LOW="NSE_FO|NIFTY_20261127_75_CE";
    private static final String CE_HIGH="NSE_FO|NIFTY_20261127_80_CE";
    private final FnoVerifiedNiftyLongOptionSourceStore daily=
        mock(FnoVerifiedNiftyLongOptionSourceStore.class);
    private final FnoVerifiedNiftyCreditSpreadSourceStore source=
        mock(FnoVerifiedNiftyCreditSpreadSourceStore.class);
    private final MarketSessionCalendar calendar=mock(MarketSessionCalendar.class);

    private Fno185186VerifiedHistoricalReplayService service(
            boolean a,boolean b,boolean c,boolean d,String account) {
        return new Fno185186VerifiedHistoricalReplayService(
            daily,source,calendar,NOW,a,b,c,d,account);
    }
    private static List<FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose> bars(String last){
        var dates=new ArrayList<LocalDate>();
        LocalDate day=LocalDate.of(2026,11,19);
        while(dates.size()<35){
            if(day.getDayOfWeek().getValue()<=5)dates.add(0,day);
            day=day.minusDays(1);
        }
        var out=new ArrayList<FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose>();
        for(int i=0;i<dates.size();i++){
            Instant close=dates.get(i).atTime(15,30).atZone(IST).toInstant();
            out.add(new FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose(
                PROVIDER,INDEX,dates.get(i),
                new BigDecimal(i==34?last:"100"),
                close.plusSeconds(60),close));
        }
        return List.copyOf(out);
    }
    private static FnoVerifiedNiftyCreditSpreadSourceStore.Leg leg(
            String key,String kind,String strike,String bid,String ask){
        return new FnoVerifiedNiftyCreditSpreadSourceStore.Leg(
            key,kind,LocalDate.of(2026,11,1),LocalDate.of(2026,11,27),
            50,new BigDecimal(strike),new BigDecimal("0.05"),
            new BigDecimal(bid),new BigDecimal(ask),10,10,1000,
            AS_OF.minusSeconds(40),AS_OF.minusSeconds(30));
    }
    private static FnoVerifiedNiftyCreditSpreadSourceStore.Proof proof(boolean bull){
        var low=bull?leg(PE_LOW,"PE","120","1.95","2")
            :leg(CE_LOW,"CE","75","5","5.10");
        var high=bull?leg(PE_HIGH,"PE","125","5","5.10")
            :leg(CE_HIGH,"CE","80","1.95","2");
        var tick=new FnoVerifiedNiftyCreditSpreadSourceStore.IndexTick(
            new BigDecimal(bull?"125":"75"),
            AS_OF.minusSeconds(40),AS_OF.minusSeconds(30));
        var risk=new FnoVerifiedNiftyCreditSpreadSourceStore.Risk(
            50,50,new BigDecimal("10"),300,2,50,5000,
            new BigDecimal("10000"),3000,3000);
        var capital=new FnoVerifiedNiftyCreditSpreadSourceStore.Capital(
            new BigDecimal("200000"),new BigDecimal("20000"),
            new BigDecimal("10000"),new BigDecimal("5000"),
            new BigDecimal("1000"));
        return new FnoVerifiedNiftyCreditSpreadSourceStore.Proof(
            PROVIDER,ACCOUNT,bull?"BULL_PUT":"BEAR_CALL",INDEX,
            low,high,tick,risk,capital,"a".repeat(64));
    }
    private void eligible(String latest){
        when(calendar.isInsideCashSession(AS_OF)).thenReturn(true);
        when(calendar.isTradingDay(any(LocalDate.class))).thenAnswer(inv ->
            ((LocalDate)inv.getArgument(0)).getDayOfWeek().getValue()<=5);
        when(daily.verifiedRecentCloses(AS_OF)).thenReturn(bars(latest));
        when(source.oneCurrentSpread(ACCOUNT,"BULL_PUT",PE_LOW,PE_HIGH,AS_OF))
            .thenReturn(Optional.of(proof(true)));
        when(source.oneCurrentSpread(ACCOUNT,"BEAR_CALL",CE_LOW,CE_HIGH,AS_OF))
            .thenReturn(Optional.of(proof(false)));
    }

    @Test void originalBullCreditSpreadRunsEvaluatorOnlyWithMarginEvidence(){
        eligible("125");
        var r=service(true,true,true,true,ACCOUNT).inspect(
            BULL,PE_LOW,PE_HIGH,AS_OF);
        assertEquals("ORIGINAL_BROKER_MARGIN_SOURCE_RESEARCH_ONLY_NOT_E2E",r.state());
        assertEquals("BULL_PUT_CREDIT_SPREAD_RESEARCH_SETUP_ONLY",r.reasonCode());
        assertEquals(35,r.verifiedCompletedNiftySessions());
        assertEquals(2,r.verifiedOptionContracts());
        assertTrue(r.historicalBrokerMarginProofConsidered());
        assertFalse(r.independentHistoricalE2E());
        assertFalse(r.paperLedgerE2E());
        assertFalse(r.liveBrokerE2E());
        assertFalse(r.brokerOrdersPlaced());
        assertFalse(r.toString().contains(ACCOUNT));
        verify(source).oneCurrentSpread(ACCOUNT,"BULL_PUT",PE_LOW,PE_HIGH,AS_OF);
        verify(source,never()).oneCurrentSpread(
            eq(ACCOUNT),eq("BEAR_CALL"),any(),any(),any());
    }

    @Test void originalBearCreditSpreadInvokesSeparateCallRiskEvaluator(){
        eligible("75");
        var r=service(true,true,true,true,ACCOUNT).inspect(
            BEAR,CE_LOW,CE_HIGH,AS_OF);
        assertEquals("ORIGINAL_BROKER_MARGIN_SOURCE_RESEARCH_ONLY_NOT_E2E",r.state());
        assertEquals("BEAR_CALL_CREDIT_SPREAD_RESEARCH_SETUP_ONLY",r.reasonCode());
        assertEquals(35,r.verifiedCompletedNiftySessions());
        assertEquals(2,r.verifiedOptionContracts());
        assertFalse(r.brokerOrdersPlaced());
    }

    @Test void missingRightsOrBrokerScopeMustDenyAllSourceQueries(){
        for(int i=0;i<4;i++){
            boolean[] bits={true,true,true,true};bits[i]=false;
            assertEquals("ORIGINAL_CREDIT_SPREAD_SOURCE_RIGHTS_UNVERIFIED",
                service(bits[0],bits[1],bits[2],bits[3],ACCOUNT)
                    .inspect(BULL,PE_LOW,PE_HIGH,AS_OF).reasonCode());
        }
        assertEquals("HISTORICAL_BROKER_ACCOUNT_MARGIN_SCOPE_UNAVAILABLE",
            service(true,true,true,true,"")
                .inspect(BULL,PE_LOW,PE_HIGH,AS_OF).reasonCode());
        verifyNoInteractions(daily,source,calendar);
    }

    @Test void originalBrokerMarginShortfallNeverClaimsHistoricalTradingReady(){
        eligible("125");
        var p=proof(true);
        var shortCash=new FnoVerifiedNiftyCreditSpreadSourceStore.Capital(
            new BigDecimal("1000"),new BigDecimal("20000"),
            new BigDecimal("10000"),new BigDecimal("5000"),
            new BigDecimal("1000"));
        when(source.oneCurrentSpread(ACCOUNT,"BULL_PUT",PE_LOW,PE_HIGH,AS_OF))
            .thenReturn(Optional.of(new FnoVerifiedNiftyCreditSpreadSourceStore.Proof(
                p.provider(),p.accountScope(),p.spreadType(),p.underlyingKey(),
                p.lower(),p.higher(),p.index(),p.risk(),shortCash,p.contractMasterDigest())));
        var r=service(true,true,true,true,ACCOUNT).inspect(
            BULL,PE_LOW,PE_HIGH,AS_OF);
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",r.state());
        assertEquals("CREDIT_SPREAD_BROKER_MARGIN_RESERVES_INSUFFICIENT",r.reasonCode());
        assertEquals(0,r.verifiedOptionContracts());
        assertFalse(r.historicalBrokerMarginProofConsidered());
    }

    @Test void absentBrokerAndOptionProofCannotBecomeAResearchDecision(){
        eligible("125");
        when(source.oneCurrentSpread(ACCOUNT,"BULL_PUT",PE_LOW,PE_HIGH,AS_OF))
            .thenReturn(Optional.empty());
        var r=service(true,true,true,true,ACCOUNT).inspect(BULL,PE_LOW,PE_HIGH,AS_OF);
        assertEquals("CREDIT_SPREAD_TWO_LEG_QUOTES_COSTS_OR_DEPTH_PROOF_MISSING",
            r.reasonCode());
        assertFalse(r.independentHistoricalE2E());
        assertFalse(r.brokerOrdersPlaced());
    }

    @Test void noFutureAsOfWrongStrategyOrCrossSegmentContractPermitted(){
        var s=service(true,true,true,true,ACCOUNT);
        assertEquals("CREDIT_SPREAD_HISTORICAL_ASOF_SCOPE_INVALID",
            s.inspect(BULL,"NSE_EQ|BAD",PE_HIGH,AS_OF).reasonCode());
        assertEquals("CREDIT_SPREAD_HISTORICAL_ASOF_SCOPE_INVALID",
            s.inspect(BULL,PE_LOW,PE_LOW,AS_OF).reasonCode());
        assertEquals("CREDIT_SPREAD_HISTORICAL_ASOF_SCOPE_INVALID",
            s.inspect(BULL,PE_LOW,PE_HIGH,NOW.instant().plusSeconds(1)).reasonCode());
        assertEquals("CREDIT_SPREAD_HISTORICAL_STRATEGY_UNSUPPORTED",
            s.inspect("strategy.STR-187",PE_LOW,PE_HIGH,AS_OF).reasonCode());
        verifyNoInteractions(daily,source);
    }

    @Test void providerExceptionNeverLeaksBrokerSecrets(){
        eligible("125");
        when(daily.verifiedRecentCloses(AS_OF))
            .thenThrow(new IllegalStateException("secret account token"));
        var r=service(true,true,true,true,ACCOUNT).inspect(
            BULL,PE_LOW,PE_HIGH,AS_OF);
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",r.state());
        assertFalse(r.toString().contains("secret"));
        assertFalse(r.brokerOrdersPlaced());
    }
}
