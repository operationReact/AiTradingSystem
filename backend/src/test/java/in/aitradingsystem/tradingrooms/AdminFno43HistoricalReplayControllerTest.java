package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/** No provider, database, AWS or broker operations in this controller test. */
class AdminFno43HistoricalReplayControllerTest {
    private static final String KEY="NSE_FO|FUT_DEC_176";
    private static final Instant AS_OF=Instant.parse("2026-11-20T11:00:00Z");
    private static final Clock CLOCK=Clock.fixed(
        Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC);
    private final Fno174VerifiedHistoricalReplayService s174=
        mock(Fno174VerifiedHistoricalReplayService.class);
    private final Fno176VerifiedHistoricalReplayService s176=
        mock(Fno176VerifiedHistoricalReplayService.class);
    private final Fno177VerifiedHistoricalReplayService s177=
        mock(Fno177VerifiedHistoricalReplayService.class);
    private final Fno181182VerifiedHistoricalReplayService options=
        mock(Fno181182VerifiedHistoricalReplayService.class);
    private final Fno183184VerifiedHistoricalReplayService debit=
        mock(Fno183184VerifiedHistoricalReplayService.class);
    private final Fno185186VerifiedHistoricalReplayService credit=
        mock(Fno185186VerifiedHistoricalReplayService.class);

    private AdminFno43HistoricalReplayController route() {
        return new AdminFno43HistoricalReplayController(s174,s176,s177,options,debit,credit,CLOCK);
    }

    @Test
    void controllerRemainsProxyableForMethodSecurity() {
        assertFalse(java.lang.reflect.Modifier.isFinal(
            AdminFno43HistoricalReplayController.class.getModifiers()));
    }

    @Test
    void endpointRequiresAdminOrOperatorAndIsGetOnly() throws Exception {
        Method method=AdminFno43HistoricalReplayController.class.getMethod(
            "inspect",String.class,String.class,Instant.class);
        assertEquals("hasAnyRole('ADMIN', 'OPERATOR')",
            method.getAnnotation(PreAuthorize.class).value());
        assertArrayEquals(new String[]{"/historical-replay"},
            method.getAnnotation(GetMapping.class).value());
        assertNotNull(method.getParameters()[0].getAnnotation(RequestParam.class));
        assertNotNull(method.getParameters()[1].getAnnotation(RequestParam.class));
        assertNotNull(method.getParameters()[2].getAnnotation(RequestParam.class));
    }

    @Test
    void eachExistingRightsJoinedSourceEvaluatorIsActuallyInvokedReadOnly() {
        when(s174.inspect(KEY,AS_OF)).thenReturn(
            new Fno174VerifiedHistoricalReplayService.Result(
                "strategy.STR-174","ORIGINAL_FINAL_SOURCE_RESEARCH_ONLY",
                "PRICE_RISING_WITH_FALLING_OI","RESEARCH_CANDIDATE",
                5,5,false,false,false,false));
        when(s176.inspect(KEY,AS_OF)).thenReturn(
            new Fno176VerifiedHistoricalReplayService.Evidence(
                "strategy.STR-176","SOURCE_JOINED_EVALUATOR_RAN",
                "FUT_MOM_UPWARD_BREAKOUT_OBSERVED",7,7,
                null,false,false,false,false));
        when(s177.inspect(KEY,AS_OF)).thenReturn(
            new Fno177VerifiedHistoricalReplayService.Result(
                "strategy.STR-177","ORIGINAL_SOURCE_EVALUATOR_RESEARCH_ONLY",
                "FUT_MR_LOW_EXTREME_REBOUND_OBSERVED",10,10,
                "a".repeat(64),"RESEARCH_CANDIDATE",
                false,false,false,false));

        for(int n:new int[]{174,176,177}){
            String id="strategy.STR-"+n;
            var response=route().inspect(id,KEY,AS_OF);
            assertEquals(200,response.getStatusCode().value());
            assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
            var result=response.getBody();
            assertNotNull(result);
            assertEquals(id,result.strategyId());
            assertEquals(AS_OF,result.checkedAsOf());
            assertTrue(result.originalSourceRowsObserved()>0);
            assertFalse(result.independentHistoricalE2EAccepted());
            assertFalse(result.paperLedgerE2EValidated());
            assertFalse(result.liveBrokerE2EValidated());
            assertFalse(result.brokerOrdersPlaced());
            assertFalse(result.toString().contains(KEY));
            assertFalse(result.toString().contains("aaaaaaaaaaaa"));
        }
        verify(s174).inspect(KEY,AS_OF);
        verify(s176).inspect(KEY,AS_OF);
        verify(s177).inspect(KEY,AS_OF);
    }

    @Test
    void sourceJoinedHistoricalCallAndPutAreRoutedWithoutPricesOrExecution() {
        when(options.inspect("strategy.STR-181",KEY,AS_OF)).thenReturn(
            new Fno181182VerifiedHistoricalReplayService.Report(
                "strategy.STR-181",
                "ORIGINAL_SOURCE_RESEARCH_EVALUATOR_RAN_NOT_E2E",
                "LONG_CALL_BULLISH_SCENARIO_RESEARCH_ONLY",
                35,1,true,true,false,false,false,false));
        when(options.inspect("strategy.STR-182",KEY,AS_OF)).thenReturn(
            new Fno181182VerifiedHistoricalReplayService.Report(
                "strategy.STR-182","BLOCKED_NOT_HISTORICAL_E2E",
                "ORIGINAL_OPTION_QUOTE_DEPTH_OI_COSTS_MISSING",
                35,0,false,false,false,false,false,false));
        var call=route().inspect("strategy.STR-181",KEY,AS_OF);
        assertEquals(200,call.getStatusCode().value());
        assertTrue(call.getHeaders().getCacheControl().contains("no-store"));
        assertEquals(35,call.getBody().originalSourceRowsObserved());
        assertEquals(35,call.getBody().originalSessionsEvaluated());
        assertFalse(call.getBody().independentHistoricalE2EAccepted());
        assertFalse(call.getBody().paperLedgerE2EValidated());
        assertFalse(call.getBody().liveBrokerE2EValidated());
        assertFalse(call.getBody().brokerOrdersPlaced());
        assertFalse(call.getBody().toString().contains(KEY));
        var put=route().inspect("strategy.STR-182",KEY,AS_OF);
        assertEquals(35,put.getBody().originalSourceRowsObserved());
        assertEquals(0,put.getBody().originalSessionsEvaluated());
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",
            put.getBody().sourceAndEvaluatorState());
        verify(options).inspect("strategy.STR-181",KEY,AS_OF);
        verify(options).inspect("strategy.STR-182",KEY,AS_OF);
        verifyNoInteractions(s174,s176,s177);
    }

    @Test
    void originalTwoLegBullAndBearSpreadRoutesAreProtectedGetOnly() throws Exception {
        Method method=AdminFno43HistoricalReplayController.class.getMethod(
            "inspectDebitSpread",String.class,String.class,String.class,Instant.class);
        assertEquals("hasAnyRole('ADMIN', 'OPERATOR')",
            method.getAnnotation(PreAuthorize.class).value());
        assertArrayEquals(new String[]{"/historical-debit-spread"},
            method.getAnnotation(GetMapping.class).value());
        for(var param:method.getParameters())
            assertNotNull(param.getAnnotation(RequestParam.class));
        String lower="NSE_FO|NIFTY_125_CE";
        String higher="NSE_FO|NIFTY_130_CE";
        when(debit.inspect("strategy.STR-183",lower,higher,AS_OF)).thenReturn(
            new Fno183184VerifiedHistoricalReplayService.Report(
                "strategy.STR-183","ORIGINAL_SOURCE_RESEARCH_EVALUATOR_RAN_NOT_E2E",
                "BULL_CALL_SPREAD_RESEARCH_SETUP_ONLY",35,2,false,false,false,false));
        var result=route().inspectDebitSpread("strategy.STR-183",lower,higher,AS_OF);
        assertEquals(200,result.getStatusCode().value());
        assertTrue(result.getHeaders().getCacheControl().contains("no-store"));
        assertEquals(35,result.getBody().originalSourceRowsObserved());
        assertEquals(35,result.getBody().originalSessionsEvaluated());
        assertFalse(result.getBody().independentHistoricalE2EAccepted());
        assertFalse(result.getBody().paperLedgerE2EValidated());
        assertFalse(result.getBody().liveBrokerE2EValidated());
        assertFalse(result.getBody().brokerOrdersPlaced());
        assertFalse(result.toString().contains(lower));
        verify(debit).inspect("strategy.STR-183",lower,higher,AS_OF);

        when(debit.inspect("strategy.STR-184",lower,higher,AS_OF)).thenReturn(
            new Fno183184VerifiedHistoricalReplayService.Report(
                "strategy.STR-184","BLOCKED_NOT_HISTORICAL_E2E",
                "DEBIT_SPREAD_TWO_LEG_QUOTES_COSTS_OR_DEPTH_PROOF_MISSING",
                0,0,false,false,false,false));
        var blocked=route().inspectDebitSpread("strategy.STR-184",lower,higher,AS_OF);
        assertEquals(0,blocked.getBody().originalSessionsEvaluated());
        assertFalse(blocked.getBody().independentHistoricalE2EAccepted());
        verifyNoInteractions(s174,s176,s177,options);
    }

    @Test
    void spreadRouteNeverAcceptsOneLegSameKeyOrUnlistedStrategy() {
        String lower="NSE_FO|NIFTY_125_CE";
        for (var pair:java.util.List.of(
                java.util.List.of(lower,lower),
                java.util.List.of("NSE_EQ|BAD","NSE_FO|HIGH"),
                java.util.List.of(lower,"NSE_FO|../../bad"))) {
            var err=assertThrows(ResponseStatusException.class,
                ()->route().inspectDebitSpread("strategy.STR-183",
                    pair.get(0),pair.get(1),AS_OF));
            assertEquals(HttpStatus.BAD_REQUEST,err.getStatusCode());
        }
        assertThrows(ResponseStatusException.class,
            ()->route().inspectDebitSpread("strategy.STR-185",lower,
                "NSE_FO|HIGH",AS_OF));
        assertThrows(ResponseStatusException.class,
            ()->route().inspectDebitSpread("strategy.STR-183",lower,
                "NSE_FO|HIGH",CLOCK.instant().plusSeconds(1)));
        verifyNoInteractions(debit);
    }

    @Test
    void originalBrokerScopedHistoricalCreditSpreadRequiresOperatorAndNoDataReturn()
            throws Exception {
        var method=AdminFno43HistoricalReplayController.class.getMethod(
            "inspectCreditSpread",String.class,String.class,String.class,Instant.class);
        assertEquals("hasAnyRole('ADMIN', 'OPERATOR')",
            method.getAnnotation(PreAuthorize.class).value());
        assertArrayEquals(new String[]{"/historical-credit-spread"},
            method.getAnnotation(GetMapping.class).value());
        for(var p:method.getParameters())assertNotNull(p.getAnnotation(RequestParam.class));
        String lower="NSE_FO|NIFTY_120_PE";
        String upper="NSE_FO|NIFTY_125_PE";
        when(credit.inspect("strategy.STR-185",lower,upper,AS_OF)).thenReturn(
            new Fno185186VerifiedHistoricalReplayService.Report(
                "strategy.STR-185",
                "ORIGINAL_BROKER_MARGIN_SOURCE_RESEARCH_ONLY_NOT_E2E",
                "BULL_PUT_CREDIT_SPREAD_RESEARCH_SETUP_ONLY",
                35,2,true,false,false,false,false));
        var response=route().inspectCreditSpread(
            "strategy.STR-185",lower,upper,AS_OF);
        assertEquals(200,response.getStatusCode().value());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertEquals(35,response.getBody().originalSourceRowsObserved());
        assertEquals(35,response.getBody().originalSessionsEvaluated());
        assertFalse(response.getBody().independentHistoricalE2EAccepted());
        assertFalse(response.getBody().paperLedgerE2EValidated());
        assertFalse(response.getBody().liveBrokerE2EValidated());
        assertFalse(response.getBody().brokerOrdersPlaced());
        assertFalse(response.toString().contains(lower));
        assertFalse(response.toString().contains("private_margin_account"));
        verify(credit).inspect("strategy.STR-185",lower,upper,AS_OF);

        when(credit.inspect("strategy.STR-186",lower,upper,AS_OF)).thenReturn(
            new Fno185186VerifiedHistoricalReplayService.Report(
                "strategy.STR-186","BLOCKED_NOT_HISTORICAL_E2E",
                "CREDIT_SPREAD_BROKER_MARGIN_RESERVES_INSUFFICIENT",
                0,0,false,false,false,false,false));
        var blocked=route().inspectCreditSpread(
            "strategy.STR-186",lower,upper,AS_OF);
        assertEquals(0,blocked.getBody().originalSessionsEvaluated());
        assertFalse(blocked.getBody().liveBrokerE2EValidated());
    }

    @Test
    void originalCreditSpreadRouteRejectsSameOptionAndFutureOrWrongStrategy() {
        String key="NSE_FO|NIFTY_120_PE";
        assertThrows(ResponseStatusException.class,()->
            route().inspectCreditSpread("strategy.STR-185",key,key,AS_OF));
        assertThrows(ResponseStatusException.class,()->
            route().inspectCreditSpread("strategy.STR-185",
                "NSE_EQ|BAD","NSE_FO|NIFTY_125_PE",AS_OF));
        assertThrows(ResponseStatusException.class,()->
            route().inspectCreditSpread("strategy.STR-185",
                key,"NSE_FO|NIFTY_125_PE",CLOCK.instant().plusSeconds(1)));
        assertThrows(ResponseStatusException.class,()->
            route().inspectCreditSpread("strategy.STR-187",
                key,"NSE_FO|NIFTY_125_PE",AS_OF));
        verifyNoInteractions(credit);
    }

    @Test
    void allOther38StrategiesAreUnavailableUntilTypedOriginalSourceAdapterExists() {
        for(int n=170;n<=212;n++){
            if(n==174||n==176||n==177||n==181||n==182)continue;
            int strategyNumber=n;
            var err=assertThrows(ResponseStatusException.class,
                ()->route().inspect("strategy.STR-"+strategyNumber,KEY,AS_OF));
            assertEquals(HttpStatus.BAD_REQUEST,err.getStatusCode());
        }
        verifyNoInteractions(s174,s176,s177,options,debit,credit);
    }

    @Test
    void untrustedContractKeyAndFutureAsOfNeverReachProtectedSource() {
        for(String bad:new String[]{"NSE_EQ|FOO","NSE_FO|BAD/../",
                                   "NSE_FO|;DROP TABLE",""}) {
            var err=assertThrows(ResponseStatusException.class,()->
                route().inspect("strategy.STR-176",bad,AS_OF));
            assertEquals(400,err.getStatusCode().value());
        }
        var future=assertThrows(ResponseStatusException.class,()->
            route().inspect("strategy.STR-176",KEY,CLOCK.instant().plusSeconds(1)));
        assertEquals(400,future.getStatusCode().value());
        verifyNoInteractions(s174,s176,s177,options,debit,credit);
    }

    @Test
    void sourceDenialStillUsesNoStoreAndCannotPromoteRealMarketReadiness(){
        when(s174.inspect(KEY,AS_OF)).thenReturn(
            new Fno174VerifiedHistoricalReplayService.Result(
                "strategy.STR-174","BLOCKED_NOT_HISTORICAL_E2E",
                "STR174_FINAL_PRICE_OR_DISCOVERY_RIGHTS_NOT_VERIFIED",
                "NOT_RUN",0,0,false,false,false,false));
        var response=route().inspect("strategy.STR-174",KEY,AS_OF);
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertEquals(0,response.getBody().originalSessionsEvaluated());
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",
            response.getBody().sourceAndEvaluatorState());
        assertFalse(response.getBody().independentHistoricalE2EAccepted());
    }
}
