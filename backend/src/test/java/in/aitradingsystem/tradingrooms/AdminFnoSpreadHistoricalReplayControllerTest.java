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
import org.springframework.web.server.ResponseStatusException;

class AdminFnoSpreadHistoricalReplayControllerTest {
    private static final Instant AS_OF=Instant.parse("2026-11-20T05:00:00Z");
    private static final Clock NOW=Clock.fixed(
        Instant.parse("2026-11-23T07:00:00Z"),ZoneOffset.UTC);
    private final Fno183186VerifiedHistoricalReplayService source=
        mock(Fno183186VerifiedHistoricalReplayService.class);
    private AdminFnoSpreadHistoricalReplayController route(){
        return new AdminFnoSpreadHistoricalReplayController(source,NOW);
    }
    @Test
    void operatorOrAdminOnlyGetWithNoCacheAndNoSourceData(){
        assertFalse(java.lang.reflect.Modifier.isFinal(
            AdminFnoSpreadHistoricalReplayController.class.getModifiers()));
        try {
            Method method=AdminFnoSpreadHistoricalReplayController.class.getMethod(
                "inspect",String.class,String.class,Instant.class);
            assertEquals("hasAnyRole('ADMIN', 'OPERATOR')",
                method.getAnnotation(PreAuthorize.class).value());
            assertArrayEquals(new String[]{"/spread-historical-replay"},
                method.getAnnotation(GetMapping.class).value());
        } catch(NoSuchMethodException absent){
            fail(absent);
        }
        for(int n=183;n<=186;n++){
            String id="strategy.STR-"+n;
            String key="NSE_FO|PRIVATE_SPREAD_"+n;
            when(source.inspect(id,key,AS_OF)).thenReturn(
                new Fno183186VerifiedHistoricalReplayService.Evidence(
                    id,AS_OF,"ORIGINAL_TWO_LEG_EVALUATOR_RESEARCH_ONLY",
                    "RESEARCH_CANDIDATE","SOURCE_RESEARCH_ONLY",35,3,
                    false,false,false,false));
            var response=route().inspect(id,key,AS_OF);
            assertEquals(200,response.getStatusCode().value());
            assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
            var x=response.getBody();
            assertNotNull(x);
            assertEquals(id,x.strategyId());
            assertEquals(35,x.originalDailySessions());
            assertEquals(3,x.originalQuoteLegs());
            assertFalse(x.independentHistoricalE2EAccepted());
            assertFalse(x.paperLedgerE2EValidated());
            assertFalse(x.liveBrokerE2EValidated());
            assertFalse(x.brokerOrdersPlaced());
            assertFalse(x.toString().contains(key));
            verify(source).inspect(id,key,AS_OF);
        }
    }
    @Test
    void arbitraryStrategyKeyOrFutureTimeDoNotCallProvider(){
        var route=route();
        for(String badKey:new String[]{
            "NSE_EQ|STOCK","NSE_FO|../../secret","NSE_FO|;DROP TABLE",""}) {
            var error=assertThrows(ResponseStatusException.class,
                ()->route.inspect("strategy.STR-183",badKey,AS_OF));
            assertEquals(HttpStatus.BAD_REQUEST,error.getStatusCode());
        }
        var other=assertThrows(ResponseStatusException.class,
            ()->route.inspect("strategy.STR-190","NSE_FO|GENUINE",AS_OF));
        assertEquals(400,other.getStatusCode().value());
        var future=assertThrows(ResponseStatusException.class,
            ()->route.inspect("strategy.STR-185","NSE_FO|GENUINE",
                NOW.instant().plusSeconds(1)));
        assertEquals(400,future.getStatusCode().value());
        verifyNoInteractions(source);
    }
    @Test
    void rightsBlockedOutputCannotBecomeHistoricalAcceptance(){
        String id="strategy.STR-183",key="NSE_FO|CONTRACT";
        when(source.inspect(id,key,AS_OF)).thenReturn(
            new Fno183186VerifiedHistoricalReplayService.Evidence(
                id,AS_OF,"BLOCKED_NOT_HISTORICAL_E2E",
                "NOT_RUN","SPREAD_SOURCE_RIGHTS_UNVERIFIED",0,0,
                false,false,false,false));
        var r=route().inspect(id,key,AS_OF).getBody();
        assertNotNull(r);
        assertEquals(0,r.originalDailySessions());
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",r.sourceAndEvaluatorState());
        assertFalse(r.independentHistoricalE2EAccepted());
    }
}
