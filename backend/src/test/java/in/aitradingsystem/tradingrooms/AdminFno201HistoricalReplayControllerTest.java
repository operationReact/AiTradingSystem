package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

/** Operator-only result test; no price/source export or broker commands. */
class AdminFno201HistoricalReplayControllerTest {
    private static final Instant AS_OF=Instant.parse("2026-11-20T05:00:00Z");
    private static final Clock NOW=Clock.fixed(
        Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC);
    private final Fno201VerifiedHistoricalReplayService source=
        mock(Fno201VerifiedHistoricalReplayService.class);
    private AdminFno201HistoricalReplayController route(){
        return new AdminFno201HistoricalReplayController(source,NOW);
    }
    @Test void originalOperatorRouteOnlyGetAndAdminRole() throws Exception{
        var type=AdminFno201HistoricalReplayController.class;
        assertFalse(java.lang.reflect.Modifier.isFinal(type.getModifiers()));
        var method=type.getMethod("inspect",Instant.class);
        assertEquals("hasAnyRole('ADMIN', 'OPERATOR')",
            method.getAnnotation(PreAuthorize.class).value());
        assertArrayEquals(new String[]{"/iv-rank-replay"},
            method.getAnnotation(GetMapping.class).value());
    }
    @Test void genuineShapeHasNoSourcePriceAndNeverClaimsHistoricalE2e(){
        when(source.inspect(AS_OF)).thenReturn(
            new Fno201VerifiedHistoricalReplayService.Report(
                "strategy.STR-201",
                "ORIGINAL_30_DAY_ATM_IV_EVALUATOR_RESEARCH_ONLY",
                "IV_RANK_HIGH_IV_PULLBACK_RESEARCH_ONLY",
                252,1,false,false,false,false));
        var response=route().inspect(AS_OF);
        assertEquals(200,response.getStatusCode().value());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        var row=response.getBody();
        assertNotNull(row);
        assertEquals(252,row.originalSessions());
        assertEquals("strategy.STR-201",row.strategyId());
        assertFalse(row.historicalMarketE2E());
        assertFalse(row.paperLedgerE2E());
        assertFalse(row.liveBrokerE2E());
        assertFalse(row.brokerOrdersPlaced());
        assertFalse(row.toString().contains("NSE_FO|"));
        verify(source).inspect(AS_OF);
    }
    @Test void rejectFutureAndMissingAsOfBeforeSource() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->route().inspect(NOW.instant().plusSeconds(1)))
                .getStatusCode());
        assertThrows(ResponseStatusException.class,()->route().inspect(null));
        verifyNoInteractions(source);
    }
    @Test void rightsDeniedOutputsSanitizedNoStore(){
        when(source.inspect(AS_OF)).thenReturn(
            new Fno201VerifiedHistoricalReplayService.Report(
                "strategy.STR-201","BLOCKED_NOT_HISTORICAL_E2E",
                "IV_RANK_ORIGINAL_HISTORY_OR_IV_RIGHTS_UNVERIFIED",
                0,0,false,false,false,false));
        var response=route().inspect(AS_OF);
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",response.getBody().state());
        assertFalse(response.getBody().historicalMarketE2E());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
    }
}
