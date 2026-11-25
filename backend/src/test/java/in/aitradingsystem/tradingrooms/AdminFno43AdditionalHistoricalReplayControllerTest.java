package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fixed research status fields only. No provider, SQL, host credentials,
 * source data or broker interaction is needed for these fixture tests.
 */
class AdminFno43AdditionalHistoricalReplayControllerTest {
    private static final String FUT="NSE_FO|FUT_DEC_V104";
    private static final String EQ="NSE_EQ|INE002A01018";
    private static final LocalDate EXPIRY=LocalDate.of(2026,11,27);
    private static final int LOT=250;
    private static final Instant AS_OF=Instant.parse("2026-11-20T11:00:00Z");
    private static final Clock CLOCK=Clock.fixed(
        Instant.parse("2026-11-22T08:00:00Z"),ZoneOffset.UTC);
    private final Fno179VerifiedLiquidityReplayService liquidity=
        mock(Fno179VerifiedLiquidityReplayService.class);
    private final Fno180VerifiedExpiryBasisReplayService basis=
        mock(Fno180VerifiedExpiryBasisReplayService.class);
    private AdminFno43AdditionalHistoricalReplayController route(){
        return new AdminFno43AdditionalHistoricalReplayController(
            liquidity,basis,CLOCK);
    }

    @Test void bothControllerGetMethodsRequireMethodSecurityAndCglibProxy(){
        assertFalse(Modifier.isFinal(
            AdminFno43AdditionalHistoricalReplayController.class.getModifiers()));
        for(Method method:AdminFno43AdditionalHistoricalReplayController.class.getMethods()){
            if(!method.getName().equals("liquidity")
                    && !method.getName().equals("basis"))continue;
            assertEquals("hasAnyRole('ADMIN', 'OPERATOR')",
                method.getAnnotation(PreAuthorize.class).value());
            assertNotNull(method.getAnnotation(GetMapping.class));
            assertEquals(1,method.getAnnotation(GetMapping.class).value().length);
        }
    }

    @Test void str179And180UseOriginalSourceServicesAndSanitizedNoStoreReceipt(){
        when(liquidity.inspect(FUT,EXPIRY,LOT,AS_OF)).thenReturn(
            new Fno179VerifiedLiquidityReplayService.Evidence(
                "strategy.STR-179","ORIGINAL_L1_EVALUATOR_RESEARCH_ONLY",
                "LIQUIDITY_RESEARCH_SCREEN_PASSED",3,3,
                "RESEARCH_PASS",false,false,false,false));
        when(basis.inspect(EQ,FUT,EXPIRY,LOT,AS_OF)).thenReturn(
            new Fno180VerifiedExpiryBasisReplayService.Result(
                "strategy.STR-180","ORIGINAL_PAIR_EVALUATOR_RESEARCH_ONLY",
                "EXPIRY_BASIS_ABOVE_FAIR_CONVERGENCE_OBSERVED",
                "RESEARCH_CANDIDATE",5,5,false,false,false,false));

        var l=route().liquidity(FUT,EXPIRY,LOT,AS_OF);
        var b=route().basis(EQ,FUT,EXPIRY,LOT,AS_OF);
        assertEquals(200,l.getStatusCode().value());
        assertEquals(200,b.getStatusCode().value());
        assertTrue(l.getHeaders().getCacheControl().contains("no-store"));
        assertTrue(b.getHeaders().getCacheControl().contains("no-store"));
        assertEquals("strategy.STR-179",l.getBody().strategyId());
        assertEquals("strategy.STR-180",b.getBody().strategyId());
        assertEquals(3,l.getBody().originalSourceObservations());
        assertEquals(5,b.getBody().originalSourceObservations());
        for(var response:new AdminFno43AdditionalHistoricalReplayController.SourceReadiness[]{
                l.getBody(),b.getBody()}){
            assertFalse(response.independentMarketE2EValidated());
            assertFalse(response.paperLedgerE2EValidated());
            assertFalse(response.liveBrokerE2EValidated());
            assertFalse(response.ordersPlaced());
            assertFalse(response.toString().contains(FUT));
            assertFalse(response.toString().contains(EQ));
        }
        verify(liquidity).inspect(FUT,EXPIRY,LOT,AS_OF);
        verify(basis).inspect(EQ,FUT,EXPIRY,LOT,AS_OF);
    }

    @Test void wrongUnderlyingInstrumentAndFutureAsOfBlockedBeforeService(){
        var route=route();
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,()->
                route.liquidity("NSE_EQ|BAD",EXPIRY,LOT,AS_OF))
                .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,()->
                route.basis("NSE_INDEX|Nifty 50",FUT,EXPIRY,LOT,AS_OF))
                .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,()->
                route.basis(EQ,FUT,EXPIRY,0,AS_OF))
                .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,()->
                route.liquidity(FUT,EXPIRY,LOT,CLOCK.instant().plusSeconds(1)))
                .getStatusCode());
        verifyNoInteractions(liquidity,basis);
    }

    @Test void sourceDeniedRemainsPlainBlockedWithoutOutputtingRawInputs(){
        when(basis.inspect(EQ,FUT,EXPIRY,LOT,AS_OF)).thenReturn(
            new Fno180VerifiedExpiryBasisReplayService.Result(
                "strategy.STR-180","BLOCKED_NOT_HISTORICAL_E2E",
                "STR180_EQUITY_FUTURES_CARRY_RIGHTS_UNVERIFIED",
                "NOT_RUN",0,0,false,false,false,false));
        var result=route().basis(EQ,FUT,EXPIRY,LOT,AS_OF).getBody();
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",result.state());
        assertEquals(0,result.originalSessionsEvaluated());
        assertFalse(result.independentMarketE2EValidated());
    }
}
