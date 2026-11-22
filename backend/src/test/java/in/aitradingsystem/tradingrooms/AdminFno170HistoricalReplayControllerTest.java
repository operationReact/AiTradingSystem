package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

class AdminFno170HistoricalReplayControllerTest {
    private static final String STOCK="NSE_EQ|INE002A01018",FUT="NSE_FO|DEC_170";
    private static final LocalDate EXP=LocalDate.of(2026,12,24);
    private static final Instant ASOF=Instant.parse("2026-11-20T05:00:00Z");
    private final Fno170VerifiedHistoricalReplayService svc=
        mock(Fno170VerifiedHistoricalReplayService.class);
    private final AdminFno170HistoricalReplayController controller=
        new AdminFno170HistoricalReplayController(svc,
            Clock.fixed(Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC));

    @Test void originalReadOnlyRouteRequiresOperatorOrAdmin() throws Exception {
        assertFalse(java.lang.reflect.Modifier.isFinal(
            AdminFno170HistoricalReplayController.class.getModifiers()));
        var method=AdminFno170HistoricalReplayController.class.getMethod(
            "inspect",String.class,String.class,LocalDate.class,int.class,Instant.class);
        assertEquals("hasAnyRole('ADMIN', 'OPERATOR')",
            method.getAnnotation(PreAuthorize.class).value());
        assertArrayEquals(new String[]{"/historical-carry-replay"},
            method.getAnnotation(GetMapping.class).value());
    }

    @Test void returnsOnlyPublicSafeFixedStatusAndCounters(){
        when(svc.inspect(STOCK,FUT,EXP,250,ASOF)).thenReturn(
            new Fno170VerifiedHistoricalReplayService.Result(
                "strategy.STR-170","ORIGINAL_INTRADAY_RESEARCH_ONLY",
                "NET_CASH_CARRY_RESEARCH_EDGE",1,1,"a".repeat(64),
                "RESEARCH_CANDIDATE",false,false,false,false));
        var response=controller.inspect(STOCK,FUT,EXP,250,ASOF);
        assertEquals(200,response.getStatusCode().value());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        var report=response.getBody();
        assertNotNull(report);
        assertEquals("strategy.STR-170",report.strategyId());
        assertEquals(1,report.originalPairedObservations());
        assertFalse(report.independentHistoricalE2E());
        assertFalse(report.paperLedgerE2E());
        assertFalse(report.liveBrokerE2E());
        assertFalse(report.ordersPlaced());
        assertFalse(report.toString().contains(STOCK));
        assertFalse(report.toString().contains(FUT));
        assertFalse(report.toString().contains("aaaaaaaaaaaa"));
    }

    @Test void untrustedContractExpiryOrFutureAsOfNeverReadsSource(){
        assertThrows(ResponseStatusException.class,()->
            controller.inspect("NSE_INDEX|Nifty 50",FUT,EXP,250,ASOF));
        assertThrows(ResponseStatusException.class,()->
            controller.inspect(STOCK,"NSE_FO|../../",EXP,250,ASOF));
        assertThrows(ResponseStatusException.class,()->
            controller.inspect(STOCK,FUT,EXP,0,ASOF));
        assertThrows(ResponseStatusException.class,()->
            controller.inspect(STOCK,FUT,EXP,250,
                Instant.parse("2026-11-23T11:00:00Z")));
        verifyNoInteractions(svc);
    }
}
