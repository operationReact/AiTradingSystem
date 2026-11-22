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

/** Protected operator read-only projection: no prices, keys or source hashes. */
class AdminFno179HistoricalReplayControllerTest {
    private static final String KEY="NSE_FO|NIFTY_DEC_179";
    private static final Instant WHEN=Instant.parse("2026-11-20T05:00:00Z");
    private static final LocalDate EXPIRY=LocalDate.of(2026,12,24);
    private final Fno179VerifiedHistoricalReplayService service=
        mock(Fno179VerifiedHistoricalReplayService.class);
    private final AdminFno179HistoricalReplayController controller =
        new AdminFno179HistoricalReplayController(service,
            Clock.fixed(Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC));

    @Test void operatorOnlyGetAndNoTradingRoutes() throws Exception {
        assertFalse(java.lang.reflect.Modifier.isFinal(
            AdminFno179HistoricalReplayController.class.getModifiers()));
        Method method=AdminFno179HistoricalReplayController.class.getMethod(
            "inspect",String.class,LocalDate.class,int.class,Instant.class);
        assertEquals("hasAnyRole('ADMIN', 'OPERATOR')",
            method.getAnnotation(PreAuthorize.class).value());
        assertArrayEquals(new String[]{"/historical-liquidity-replay"},
            method.getAnnotation(GetMapping.class).value());
    }
    @Test void onlySanitizedCountsAndFixedReasonLeaveOperatorBoundary(){
        when(service.inspect(KEY,EXPIRY,25,WHEN)).thenReturn(
            new Fno179VerifiedHistoricalReplayService.Result(
                "strategy.STR-179","ORIGINAL_SOURCE_RESEARCH_ONLY",
                "LIQUIDITY_RESEARCH_SCREEN_PASSED",3,3,
                "a".repeat(64),"RESEARCH_PASS",false,false,false,false));
        var response=controller.inspect(KEY,EXPIRY,25,WHEN);
        assertEquals(200,response.getStatusCode().value());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        var view=response.getBody();
        assertNotNull(view);
        assertEquals("strategy.STR-179",view.strategyId());
        assertEquals("ORIGINAL_SOURCE_RESEARCH_ONLY",view.state());
        assertEquals(3,view.originalQuoteObservations());
        assertFalse(view.independentHistoricalE2E());
        assertFalse(view.paperLedgerE2E());
        assertFalse(view.liveBrokerE2E());
        assertFalse(view.ordersPlaced());
        assertFalse(view.toString().contains(KEY));
        assertFalse(view.toString().contains("aaaaaaaaaaaaaaaa"));
        verify(service).inspect(KEY,EXPIRY,25,WHEN);
    }
    @Test void untrustedIdentityAndFutureDecisionCannotAccessSource(){
        for(String key:new String[]{"NSE_EQ|X","NSE_FO|../../","NSE_FO|FOO;DROP",""}){
            assertThrows(ResponseStatusException.class,
                ()->controller.inspect(key,EXPIRY,25,WHEN));
        }
        assertThrows(ResponseStatusException.class,
            ()->controller.inspect(KEY,EXPIRY,0,WHEN));
        assertThrows(ResponseStatusException.class,
            ()->controller.inspect(KEY,EXPIRY,25,
                Instant.parse("2026-11-23T05:00:00Z")));
        verifyNoInteractions(service);
    }
}
