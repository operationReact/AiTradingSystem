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

    private AdminFno43HistoricalReplayController route() {
        return new AdminFno43HistoricalReplayController(s174,s176,s177,CLOCK);
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
    void allOther40StrategiesAreUnavailableUntilTypedOriginalSourceAdapterExists() {
        for(int n=170;n<=212;n++){
            if(n==174||n==176||n==177)continue;
            int strategyNumber=n;
            var err=assertThrows(ResponseStatusException.class,
                ()->route().inspect("strategy.STR-"+strategyNumber,KEY,AS_OF));
            assertEquals(HttpStatus.BAD_REQUEST,err.getStatusCode());
        }
        verifyNoInteractions(s174,s176,s177);
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
        verifyNoInteractions(s174,s176,s177);
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
