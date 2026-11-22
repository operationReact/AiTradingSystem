package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

class AdminFno180HistoricalReplayControllerTest {
    private static final String STOCK="NSE_EQ|INE002A01018";
    private static final String FUT="NSE_FO|DEC_180";
    private static final LocalDate EXP=LocalDate.of(2026,11,27);
    private static final Instant ASOF=Instant.parse("2026-11-20T11:00:00Z");
    private final Fno180VerifiedHistoricalReplayService service=
        mock(Fno180VerifiedHistoricalReplayService.class);
    private final AdminFno180HistoricalReplayController controller=
        new AdminFno180HistoricalReplayController(service,
            Clock.fixed(Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC));

    @Test void roleProtectedOriginalPairsReadOnlyGet() throws Exception {
        assertFalse(java.lang.reflect.Modifier.isFinal(
            AdminFno180HistoricalReplayController.class.getModifiers()));
        Method method=AdminFno180HistoricalReplayController.class.getMethod(
            "inspect",String.class,String.class,LocalDate.class,int.class,Instant.class);
        assertArrayEquals(new String[]{"/historical-basis-replay"},
            method.getAnnotation(GetMapping.class).value());
        assertEquals("hasAnyRole('ADMIN', 'OPERATOR')",
            method.getAnnotation(PreAuthorize.class).value());
    }
    @Test void resultNeverReturnsPrivateQuotesOrContractIdentities(){
        when(service.inspect(STOCK,FUT,EXP,250,ASOF)).thenReturn(
            new Fno180VerifiedHistoricalReplayService.Result(
                "strategy.STR-180","ORIGINAL_PAIR_EVALUATOR_RESEARCH_ONLY",
                "EXPIRY_BASIS_ABOVE_FAIR_CONVERGENCE_OBSERVED",
                5,5,"a".repeat(64),"RESEARCH_CANDIDATE",
                false,false,false,false));
        var res=controller.inspect(STOCK,FUT,EXP,250,ASOF);
        assertEquals(200,res.getStatusCode().value());
        assertTrue(res.getHeaders().getCacheControl().contains("no-store"));
        var body=res.getBody();
        assertNotNull(body);
        assertEquals(5,body.originalPairedSessionsObserved());
        assertEquals("strategy.STR-180",body.strategyId());
        assertFalse(body.independentHistoricalE2E());
        assertFalse(body.paperLedgerE2E());
        assertFalse(body.liveBrokerE2E());
        assertFalse(body.ordersPlaced());
        assertFalse(body.toString().contains(STOCK));
        assertFalse(body.toString().contains(FUT));
        assertFalse(body.toString().contains("aaaaaaaaaaaa"));
    }
    @Test void malformedIdentityAndFutureDecisionNotSentToSource(){
        for(String equity:new String[]{"NSE_INDEX|Nifty","NSE_EQ|../", ""})
            assertThrows(ResponseStatusException.class,
                ()->controller.inspect(equity,FUT,EXP,250,ASOF));
        assertThrows(ResponseStatusException.class,()->
            controller.inspect(STOCK,FUT,EXP,0,ASOF));
        assertThrows(ResponseStatusException.class,()->
            controller.inspect(STOCK,FUT,EXP,250,
                Instant.parse("2026-11-23T05:00:00Z")));
        verifyNoInteractions(service);
    }
}
