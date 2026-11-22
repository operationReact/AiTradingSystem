package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

/** No AWS, raw prices or broker order requests. */
class AdminFno180HistoricalReplayControllerTest {
    private static final String SPOT="NSE_EQ|INE002A01018";
    private static final String FUT="NSE_FO|DEC_180";
    private static final Instant AS_OF=Instant.parse("2026-11-20T11:00:00Z");
    private static final Clock NOW=Clock.fixed(
        Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC);
    private static final LocalDate EXPIRY=LocalDate.of(2026,11,27);
    private final Fno180VerifiedHistoricalReplayService service=
        mock(Fno180VerifiedHistoricalReplayService.class);
    private AdminFno180HistoricalReplayController controller(){
        return new AdminFno180HistoricalReplayController(service,NOW);
    }

    @Test void roleProtectedGetOnlyAndNoStoreMustRemain() throws Exception {
        var type=AdminFno180HistoricalReplayController.class;
        assertFalse(java.lang.reflect.Modifier.isFinal(type.getModifiers()));
        var method=type.getMethod("inspect",String.class,String.class,
            LocalDate.class,int.class,Instant.class);
        assertEquals("hasAnyRole('ADMIN', 'OPERATOR')",
            method.getAnnotation(PreAuthorize.class).value());
        assertArrayEquals(new String[]{"/expiry-basis-replay"},
            method.getAnnotation(GetMapping.class).value());
    }

    @Test void certifiedShapeNeverExposesUnderlyingContractsOrClaimsE2e(){
        when(service.inspect(SPOT,FUT,EXPIRY,250,AS_OF)).thenReturn(
            new Fno180VerifiedHistoricalReplayService.Result(
                "strategy.STR-180","ORIGINAL_PAIR_EVALUATOR_RESEARCH_ONLY",
                "EXPIRY_BASIS_ABOVE_FAIR_CONVERGENCE_OBSERVED",
                5,5,false,false,false,false));
        var response=controller().inspect(SPOT,FUT,EXPIRY,250,AS_OF);
        assertEquals(200,response.getStatusCode().value());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        var r=response.getBody();
        assertNotNull(r);
        assertEquals("strategy.STR-180",r.strategyId());
        assertEquals(5,r.originalSourcePairs());
        assertEquals(5,r.completedSessionsEvaluated());
        assertFalse(r.independentlyVerifiedHistoricalMarketE2E());
        assertFalse(r.paperLedgerE2E());
        assertFalse(r.liveBrokerE2E());
        assertFalse(r.ordersPlaced());
        assertFalse(r.toString().contains(SPOT));
        assertFalse(r.toString().contains(FUT));
        verify(service).inspect(SPOT,FUT,EXPIRY,250,AS_OF);
    }

    @Test void invalidScopeBeforeAnyServiceCall(){
        for(String bad:new String[]{"NSE_INDEX|Nifty 50","../","NSE_EQ|BAD/../"}) {
            assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,()->
                    controller().inspect(bad,FUT,EXPIRY,250,AS_OF))
                    .getStatusCode());
        }
        assertThrows(ResponseStatusException.class,()->
            controller().inspect(SPOT,"NSE_FO|../",EXPIRY,250,AS_OF));
        assertThrows(ResponseStatusException.class,()->
            controller().inspect(SPOT,FUT,EXPIRY,0,AS_OF));
        assertThrows(ResponseStatusException.class,()->
            controller().inspect(SPOT,FUT,EXPIRY,250,NOW.instant().plusSeconds(1)));
        verifyNoInteractions(service);
    }

    @Test void deniedOriginalSourceStateIsSanitizedNoStore(){
        when(service.inspect(SPOT,FUT,EXPIRY,250,AS_OF)).thenReturn(
            new Fno180VerifiedHistoricalReplayService.Result(
                "strategy.STR-180","BLOCKED_NOT_HISTORICAL_E2E",
                "STR180_SPOT_FUTURE_CARRY_RIGHTS_UNVERIFIED",
                0,0,false,false,false,false));
        var result=controller().inspect(SPOT,FUT,EXPIRY,250,AS_OF);
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",result.getBody().state());
        assertEquals(0,result.getBody().completedSessionsEvaluated());
        assertFalse(result.getBody().independentlyVerifiedHistoricalMarketE2E());
        assertTrue(result.getHeaders().getCacheControl().contains("no-store"));
    }
}
