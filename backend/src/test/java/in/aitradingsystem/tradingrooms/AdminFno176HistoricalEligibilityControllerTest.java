package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import in.aitradingsystem.marketdata.FnoVerifiedCompletedOhlcvOiStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

class AdminFno176HistoricalEligibilityControllerTest {
    private static final Instant NOW=Instant.parse("2026-11-24T06:00:00Z");
    private final FnoVerifiedCompletedOhlcvOiStore store=
        mock(FnoVerifiedCompletedOhlcvOiStore.class);
    private final AdminFno176HistoricalEligibilityController controller=
        new AdminFno176HistoricalEligibilityController(
            store, Clock.fixed(NOW, ZoneOffset.UTC));

    private AdminFno176HistoricalEligibilityController.EligibilitySnapshot read(){
        var response=controller.inspect();
        assertNotNull(response.getBody());
        assertEquals(CacheControl.noStore().getHeaderValue(),
            response.getHeaders().getCacheControl());
        return response.getBody();
    }

    @Test void rightJoinedWindowIsOnlyPotentialInputNeverHistoricalE2E(){
        when(store.inspectHistoricalEligibility(NOW)).thenReturn(
            new FnoVerifiedCompletedOhlcvOiStore.HistoricalEligibility(43,21,2));
        var e=read();
        assertEquals("strategy.STR-176",e.strategyId());
        assertEquals(43,e.originalRows());
        assertEquals(21,e.rightsJoinedAsOfRows());
        assertEquals(2,e.possibleSevenSessionWindows());
        assertEquals("POTENTIAL_SOURCE_INPUT_ONLY_NOT_E2E",e.state());
        assertFalse(e.independentHistoricalE2EValidated());
        assertFalse(e.ordersPlaced());
        verify(store).inspectHistoricalEligibility(NOW);
    }

    @Test void missingSchemaEmptySourceAndRightsGapAreDistinct(){
        when(store.inspectHistoricalEligibility(NOW)).thenReturn(null);
        assertEquals("SOURCE_STORE_UNAVAILABLE_OR_UNVERIFIED",read().state());
        when(store.inspectHistoricalEligibility(NOW)).thenReturn(
            new FnoVerifiedCompletedOhlcvOiStore.HistoricalEligibility(0,0,0));
        assertEquals("NO_ORIGINAL_V102_SOURCE_ROWS",read().state());
        when(store.inspectHistoricalEligibility(NOW)).thenReturn(
            new FnoVerifiedCompletedOhlcvOiStore.HistoricalEligibility(30,0,0));
        assertEquals("NO_RIGHTS_JOINED_ASOF_SOURCE_ROWS",read().state());
        when(store.inspectHistoricalEligibility(NOW)).thenReturn(
            new FnoVerifiedCompletedOhlcvOiStore.HistoricalEligibility(30,5,0));
        assertEquals("NO_SEVEN_SESSION_CANDIDATE_WINDOW",read().state());
    }

    @Test void invalidCountsAndDatabaseErrorsFailClosedWithoutLeakage(){
        when(store.inspectHistoricalEligibility(NOW)).thenReturn(
            new FnoVerifiedCompletedOhlcvOiStore.HistoricalEligibility(1,2,2));
        var invalid=read();
        assertEquals("SOURCE_STORE_UNAVAILABLE_OR_UNVERIFIED",invalid.state());
        assertEquals(-1,invalid.originalRows());
        assertFalse(invalid.independentHistoricalE2EValidated());
        when(store.inspectHistoricalEligibility(NOW))
            .thenThrow(new IllegalStateException("private SQL password"));
        var err=read();
        assertEquals("SOURCE_STORE_UNAVAILABLE_OR_UNVERIFIED",err.state());
        assertFalse(err.toString().contains("password"));
        assertFalse(err.ordersPlaced());
    }

    @Test void routeIsAdminOperatorOnlyReadOnlyGet() throws Exception {
        var method=AdminFno176HistoricalEligibilityController.class
            .getMethod("inspect");
        assertEquals("/str176-original-source",
            method.getAnnotation(GetMapping.class).value()[0]);
        assertEquals("hasAnyRole('ADMIN', 'OPERATOR')",
            method.getAnnotation(PreAuthorize.class).value());
    }
}
