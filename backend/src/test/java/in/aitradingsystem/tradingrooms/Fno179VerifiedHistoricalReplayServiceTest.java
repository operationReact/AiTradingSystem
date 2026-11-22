package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import in.aitradingsystem.marketdata.FnoVerifiedIntradayLiquidityStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** All quotes are deliberately fictional; no independent E2E acceptance. */
class Fno179VerifiedHistoricalReplayServiceTest {
    private static final String KEY="NSE_FO|FUT_DEC_179";
    private static final Instant AS_OF=Instant.parse("2026-11-20T05:00:00Z");
    private static final LocalDate EXPIRY=LocalDate.of(2026,12,24);
    private static final Clock CLOCK=Clock.fixed(
        Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC);
    private final FnoVerifiedIntradayLiquidityStore store=
        mock(FnoVerifiedIntradayLiquidityStore.class);
    private final MarketSessionCalendar cal=mock(MarketSessionCalendar.class);
    private Fno179VerifiedHistoricalReplayService service(boolean a,boolean b,boolean c) {
        return new Fno179VerifiedHistoricalReplayService(store,cal,CLOCK,a,b,c);
    }
    private static FnoVerifiedIntradayLiquidityStore.Snapshot row(
        int sec,String provider,Instant observed) {
        return new FnoVerifiedIntradayLiquidityStore.Snapshot(
            provider,KEY,"NSE_INDEX|Nifty 50",EXPIRY,25,"FUTURE",
            BigDecimal.valueOf(20000+sec),
            BigDecimal.valueOf(20004+sec),
            100,125,20000,5100+sec,AS_OF.minusSeconds(35-sec),
            observed,AS_OF.minusSeconds(75),observed);
    }
    private static List<FnoVerifiedIntradayLiquidityStore.Snapshot> rows() {
        return List.of(
            row(0,"UPSTOX_V3",AS_OF.minusSeconds(34)),
            row(15,"UPSTOX_V3",AS_OF.minusSeconds(19)),
            row(30,"UPSTOX_V3",AS_OF.minusSeconds(4)));
    }
    private void ready(List<FnoVerifiedIntradayLiquidityStore.Snapshot> observations) {
        when(cal.isInsideFnoSession(AS_OF)).thenReturn(true);
        when(store.latestThreeFutures(KEY,EXPIRY,25,AS_OF)).thenReturn(observations);
    }

    @Test void deniesAnyRightsMissingBeforeTouchingSource() {
        for(int denied=0;denied<3;denied++) {
            boolean[] flags={true,true,true};flags[denied]=false;
            var result=service(flags[0],flags[1],flags[2])
                .inspect(KEY,EXPIRY,25,AS_OF);
            assertEquals("STR179_ORIGINAL_QUOTE_OI_VOLUME_RIGHTS_MISSING",result.reason());
            assertEquals(0,result.evaluatorSnapshots());
            assertFalse(result.independentHistoricalE2E());
        }
        verifyNoInteractions(store,cal);
    }

    @Test void trueOriginalSourcePathRunsTheActualLiquidityEvaluator() {
        ready(rows());
        var result=service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF);
        assertEquals("ORIGINAL_LIQUIDITY_SOURCE_EVALUATED_RESEARCH_ONLY",result.state());
        assertEquals("LIQUIDITY_RESEARCH_SCREEN_PASSED",result.reason());
        assertEquals(3,result.originalSourceSnapshots());
        assertEquals(3,result.evaluatorSnapshots());
        assertFalse(result.independentHistoricalE2E());
        assertFalse(result.paperLedgerE2E());
        assertFalse(result.liveBrokerE2E());
        assertFalse(result.brokerOrdersPlaced());
        verify(store).latestThreeFutures(KEY,EXPIRY,25,AS_OF);
    }

    @Test void missingOriginalSnapshotOrClosedExchangeBlocks() {
        ready(rows().subList(0,2));
        assertEquals("STR179_THREE_ORIGINAL_SNAPSHOTS_MISSING",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reason());
        when(cal.isInsideFnoSession(AS_OF)).thenReturn(false);
        assertEquals("STR179_EXCHANGE_SESSION_NOT_VERIFIED",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reason());
    }

    @Test void futureKnownAndMixedProviderOrDuplicateQuotesBlock() {
        var good=rows();
        var mixed=new ArrayList<>(good);
        mixed.set(2,row(30,"ANGEL_ONE_SMARTAPI",AS_OF.minusSeconds(4)));
        ready(mixed);
        assertEquals("STR179_ORIGINAL_QUOTE_OR_IDENTITY_AMBIGUOUS",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reason());
        var future=new ArrayList<>(good);
        future.set(2,row(30,"UPSTOX_V3",AS_OF.plusSeconds(1)));
        ready(future);
        assertEquals("STR179_ORIGINAL_QUOTE_OR_IDENTITY_AMBIGUOUS",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reason());
        var duplicate=new ArrayList<>(good);
        duplicate.set(2,good.get(1));
        ready(duplicate);
        assertEquals("STR179_ORIGINAL_QUOTE_OR_IDENTITY_AMBIGUOUS",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reason());
    }

    @Test void invalidScopeAndSqlExceptionNeverLeakSensitiveSource() {
        var service=service(true,true,true);
        assertEquals("STR179_HISTORICAL_SCOPE_INVALID",
            service.inspect("NSE_EQ|BAD",EXPIRY,25,AS_OF).reason());
        assertEquals("STR179_HISTORICAL_SCOPE_INVALID",
            service.inspect(KEY,EXPIRY,25,CLOCK.instant().plusSeconds(1)).reason());
        verifyNoInteractions(store);
        when(cal.isInsideFnoSession(AS_OF)).thenReturn(true);
        when(store.latestThreeFutures(KEY,EXPIRY,25,AS_OF))
            .thenThrow(new IllegalArgumentException("secret bearer token"));
        var result=service.inspect(KEY,EXPIRY,25,AS_OF);
        assertEquals("STR179_ORIGINAL_SOURCE_UNAVAILABLE",result.reason());
        assertFalse(result.toString().contains("bearer"));
    }
}
