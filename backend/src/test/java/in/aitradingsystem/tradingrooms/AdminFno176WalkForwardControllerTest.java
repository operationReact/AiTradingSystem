package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

/** Read-only no-order contract, not a historical market acceptance test. */
class AdminFno176WalkForwardControllerTest {
    @Test void actualSourceWalkForwardCountsNeverLeakContractKeysOrSourceHashes() {
        var svc = mock(Fno176SourceLinkedWalkForwardV1.class);
        var start=LocalDate.parse("2026-11-19");
        var end=LocalDate.parse("2026-11-24");
        var train=LocalDate.parse("2026-11-20");
        var report=new Fno176SourceLinkedWalkForwardV1.Report(
            Fno176SourceLinkedWalkForwardV1.CONTRACT,
            "strategy.STR-176","SOURCE_LINKED_WALK_FORWARD_NO_ORIGINAL_FILLS_NOT_E2E",
            "STR176_INDEPENDENT_ORIGINAL_FILLS_REQUIRED",
            4,2,2,1,0,List.of(),null,false,false,false);
        when(svc.inspect("NSE_FO|PRIVATE_176",start,end,train,null)).thenReturn(report);
        var controller=new AdminFno176WalkForwardController(svc);
        var response=controller.inspect("NSE_FO|PRIVATE_176",start,end,train);
        assertEquals(CacheControl.noStore().getHeaderValue(),
            response.getHeaders().getCacheControl());
        var output=response.getBody();
        assertNotNull(output);
        assertEquals(4,output.sessionsReplayed());
        assertEquals(2,output.trainingDecisions());
        assertEquals(2,output.outOfSampleDecisions());
        assertFalse(output.independentlyHistoricalE2ECertified());
        assertFalse(output.paperOrdersPlaced());
        assertFalse(output.liveOrdersPlaced());
        assertFalse(output.toString().contains("PRIVATE_176"));
        verify(svc).inspect("NSE_FO|PRIVATE_176",start,end,train,null);
    }

    @Test void routeIsExplicitProtectedReadOnlyAndProxyable() throws Exception {
        var method=AdminFno176WalkForwardController.class.getMethod(
            "inspect",String.class,LocalDate.class,LocalDate.class,LocalDate.class);
        assertEquals("/str176-walkforward",
            method.getAnnotation(GetMapping.class).value()[0]);
        assertEquals("hasAnyRole('ADMIN', 'OPERATOR')",
            method.getAnnotation(PreAuthorize.class).value());
        assertFalse(java.lang.reflect.Modifier.isFinal(
            AdminFno176WalkForwardController.class.getModifiers()),
            "Spring Security must be able to proxy protected controller");
    }
}
