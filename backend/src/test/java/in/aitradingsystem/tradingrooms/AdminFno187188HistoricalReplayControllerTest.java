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

/** Admin route tests have no broker or market-data network access. */
class AdminFno187188HistoricalReplayControllerTest {
    private static final Instant AS_OF=Instant.parse("2026-11-20T05:00:00Z");
    private static final Clock CLOCK=Clock.fixed(
        Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC);
    private final Fno187188VerifiedHistoricalReplayService source=
        mock(Fno187188VerifiedHistoricalReplayService.class);
    private AdminFno187188HistoricalReplayController route(){
        return new AdminFno187188HistoricalReplayController(source,CLOCK);
    }

    @Test void routeIsProxyableOperatorGetAndCannotAcceptBrokerOrStockParams()
            throws Exception {
        assertFalse(java.lang.reflect.Modifier.isFinal(
            AdminFno187188HistoricalReplayController.class.getModifiers()));
        Method m=AdminFno187188HistoricalReplayController.class.getMethod(
            "inspect",String.class,Instant.class);
        assertEquals("hasAnyRole('ADMIN', 'OPERATOR')",
            m.getAnnotation(PreAuthorize.class).value());
        assertArrayEquals(new String[]{"/owned-equity-historical-replay"},
            m.getAnnotation(GetMapping.class).value());
        assertEquals(2,m.getParameterCount());
        for(var p:m.getParameters())assertNotNull(p.getAnnotation(RequestParam.class));
    }

    @Test void originalBrokerOwnedPutAndCoveredCallReturnOnlySanitizedResearchCounts(){
        for(var id:java.util.List.of("strategy.STR-187","strategy.STR-188")){
            when(source.inspect(id,AS_OF)).thenReturn(
                new Fno187188VerifiedHistoricalReplayService.Evidence(
                    id,AS_OF,"ORIGINAL_OWNED_STOCK_EVALUATOR_RESEARCH_ONLY",
                    id.endsWith("187")?"PROTECTIVE_PUT_RESEARCH_ONLY":"COVERED_CALL_RESEARCH_ONLY",
                    35,1,false,false,false,false));
            var response=route().inspect(id,AS_OF);
            assertEquals(200,response.getStatusCode().value());
            assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
            var result=response.getBody();
            assertEquals(35,result.originalEquitySessions());
            assertEquals(1,result.verifiedSettledOwnedOptionContracts());
            assertFalse(result.independentlyAcceptedHistoricalE2E());
            assertFalse(result.paperLedgerE2E());
            assertFalse(result.liveBrokerE2E());
            assertFalse(result.ordersPlaced());
            assertFalse(result.toString().contains("NSE_EQ|"));
            assertFalse(result.toString().contains("private_owned_account"));
            verify(source).inspect(id,AS_OF);
        }
    }

    @Test void failedOriginalBrokerCustodyNeverPromotesPaperOrLiveReadiness(){
        when(source.inspect("strategy.STR-188",AS_OF)).thenReturn(
            new Fno187188VerifiedHistoricalReplayService.Evidence(
                "strategy.STR-188",AS_OF,"BLOCKED_NOT_HISTORICAL_E2E",
                "OWNED_STOCK_BROKER_SETTLED_LOT_PROOF_MISSING",
                0,0,false,false,false,false));
        var response=route().inspect("strategy.STR-188",AS_OF);
        assertEquals(200,response.getStatusCode().value());
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",response.getBody().state());
        assertEquals(0,response.getBody().originalEquitySessions());
        assertFalse(response.getBody().independentlyAcceptedHistoricalE2E());
        assertFalse(response.getBody().ordersPlaced());
    }

    @Test void unsupportedStrategyAndFutureAsOfCannotReachBrokerSource(){
        for(var id:java.util.List.of("strategy.STR-186","strategy.STR-189","invalid")){
            var error=assertThrows(ResponseStatusException.class,
                ()->route().inspect(id,AS_OF));
            assertEquals(HttpStatus.BAD_REQUEST,error.getStatusCode());
        }
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->route().inspect("strategy.STR-187",CLOCK.instant().plusSeconds(1)))
                .getStatusCode());
        verifyNoInteractions(source);
    }
}
