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

class AdminFnoRemainingHistoricalReplayControllerTest {
    private static final Instant AS_OF=Instant.parse("2026-11-20T05:00:00Z");
    private static final Clock CLOCK=Clock.fixed(
        Instant.parse("2026-11-23T07:00:00Z"),ZoneOffset.UTC);
    private final Fno187212OriginalSourceReplayService svc=
        mock(Fno187212OriginalSourceReplayService.class);
    private AdminFnoRemainingHistoricalReplayController controller() {
        return new AdminFnoRemainingHistoricalReplayController(svc,CLOCK);
    }

    @Test
    void onlyAdminOperatorGetCanSeeSanitizedOriginalSourceStatus() throws Exception {
        assertFalse(java.lang.reflect.Modifier.isFinal(
            AdminFnoRemainingHistoricalReplayController.class.getModifiers()));
        Method route=AdminFnoRemainingHistoricalReplayController.class.getMethod(
            "inspect",String.class,Instant.class);
        assertEquals("hasAnyRole('ADMIN', 'OPERATOR')",
            route.getAnnotation(PreAuthorize.class).value());
        assertArrayEquals(new String[]{"/remaining-original-replay"},
            route.getAnnotation(GetMapping.class).value());
        for(int n=187;n<=212;n++) {
            String id="strategy.STR-"+n;
            when(svc.inspect(id,AS_OF)).thenReturn(
                new Fno187212OriginalSourceReplayService.Proof(id,AS_OF,
                    "SOURCE_RESEARCH_EVALUATOR_RAN_NOT_E2E",
                    "ORIGINAL_RESEARCH_ONLY","NO_SIGNAL",35,3,
                    false,false,false,false));
            var response=controller().inspect(id,AS_OF);
            assertEquals(200,response.getStatusCode().value());
            assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
            var x=response.getBody();
            assertNotNull(x);
            assertEquals(id,x.strategyId());
            assertEquals(35,x.originalSessionCount());
            assertFalse(x.independentlyAcceptedHistoricalE2E());
            assertFalse(x.paperLedgerE2E());
            assertFalse(x.liveBrokerE2E());
            assertFalse(x.brokerOrdersPlaced());
        }
    }

    @Test
    void wrongStrategyOrFutureDecisionCannotCallSource(){
        for(String bad:new String[]{
            "strategy.STR-170","strategy.STR-186","strategy.STR-213",""}) {
            var e=assertThrows(ResponseStatusException.class,()->
                controller().inspect(bad,AS_OF));
            assertEquals(HttpStatus.BAD_REQUEST,e.getStatusCode());
        }
        var future=assertThrows(ResponseStatusException.class,()->
            controller().inspect("strategy.STR-187",CLOCK.instant().plusSeconds(1)));
        assertEquals(HttpStatus.BAD_REQUEST,future.getStatusCode());
        verifyNoInteractions(svc);
    }
}
