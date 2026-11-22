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

/**
 * Fictional snapshots test the genuine STR-179 evaluator and original SQL
 * adapter; they are never evidence of real rights or market E2E.
 */
class Fno179VerifiedHistoricalReplayServiceTest {
    private static final String KEY="NSE_FO|NIFTY_DEC_179";
    private static final LocalDate EXPIRY=LocalDate.of(2026,12,24);
    private static final Instant AS_OF=Instant.parse("2026-11-20T05:00:00Z");
    private static final Clock NOW=Clock.fixed(
        Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC);
    private static final String HASH="a".repeat(64);
    private final FnoVerifiedIntradayLiquidityStore source =
        mock(FnoVerifiedIntradayLiquidityStore.class);
    private final MarketSessionCalendar calendar=mock(MarketSessionCalendar.class);

    private Fno179VerifiedHistoricalReplayService service(
            boolean research,boolean l1,boolean discovery) {
        return new Fno179VerifiedHistoricalReplayService(
            source,calendar,NOW,research,l1,discovery);
    }
    private static FnoVerifiedIntradayLiquidityStore.ReplayProof row(
            int secondsAgo,String provider,String originalDigest) {
        Instant tick=AS_OF.minusSeconds(secondsAgo);
        var snapshot=new FnoVerifiedIntradayLiquidityStore.Snapshot(
            provider,KEY,"NSE_INDEX|Nifty 50",EXPIRY,25,"FUTURE",
            BigDecimal.valueOf(20000+(35-secondsAgo)),
            BigDecimal.valueOf(20004+(35-secondsAgo)),
            100,125,20000,5000+(35-secondsAgo)*5L,
            tick,tick.plusSeconds(1),tick.minusSeconds(35),
            tick.plusSeconds(1));
        return new FnoVerifiedIntradayLiquidityStore.ReplayProof(snapshot,
            HASH,HASH,originalDigest,HASH,HASH);
    }
    private static List<FnoVerifiedIntradayLiquidityStore.ReplayProof> originalRows(){
        return List.of(
            row(35,"UPSTOX_V3",HASH),
            row(20,"UPSTOX_V3","b".repeat(64)),
            row(5,"UPSTOX_V3","c".repeat(64)));
    }
    private void allow(List<FnoVerifiedIntradayLiquidityStore.ReplayProof> input){
        when(calendar.isInsideFnoSession(AS_OF)).thenReturn(true);
        when(source.latestThreeReplayFutures(KEY,EXPIRY,25,AS_OF))
            .thenReturn(input);
    }

    @Test
    void realEvaluatorRunsForOriginalRightsJoinedThreeL1SourcesNoOrders() {
        allow(originalRows());
        var result=service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF);
        assertEquals("ORIGINAL_SOURCE_RESEARCH_ONLY",result.state());
        assertEquals("RESEARCH_PASS",result.evaluatorStatus());
        assertEquals("LIQUIDITY_RESEARCH_SCREEN_PASSED",result.reasonCode());
        assertEquals(3,result.sourceRows());
        assertEquals(3,result.originalQuoteObservations());
        assertEquals(64,result.sourceInputDigest().length());
        assertFalse(result.independentHistoricalE2E());
        assertFalse(result.paperLedgerE2E());
        assertFalse(result.liveBrokerE2E());
        assertFalse(result.ordersPlaced());
        assertFalse(result.toString().contains(KEY));
        assertFalse(result.toString().contains("20000"));
        verify(source).latestThreeReplayFutures(KEY,EXPIRY,25,AS_OF);
    }

    @Test
    void eachUnverifiedRightDeniesBeforeAnyMarketSourceRead(){
        for(int n=0;n<3;n++){
            boolean[] f={true,true,true};
            f[n]=false;
            var result=service(f[0],f[1],f[2])
                .inspect(KEY,EXPIRY,25,AS_OF);
            assertEquals("STR179_SOURCE_RIGHTS_OR_DISCOVERY_UNVERIFIED",
                result.reasonCode());
            assertNull(result.sourceInputDigest());
        }
        verifyNoInteractions(source,calendar);
    }

    @Test
    void marketClosedOrInvalidContractScopeNeverReadsSql() {
        assertEquals("STR179_ORIGINAL_FNO_EXCHANGE_SESSION_UNVERIFIED",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reasonCode());
        for(var bad:new String[]{"NSE_EQ|NOT_FO","NSE_FO|../../etc/passwd",""}) {
            assertEquals("STR179_SCOPE_INVALID",
                service(true,true,true).inspect(bad,EXPIRY,25,AS_OF).reasonCode());
        }
        assertEquals("STR179_SCOPE_INVALID",
            service(true,true,true).inspect(KEY,EXPIRY,0,AS_OF).reasonCode());
        assertEquals("STR179_SCOPE_INVALID",
            service(true,true,true).inspect(KEY,EXPIRY,25,NOW.instant().plusSeconds(1))
                .reasonCode());
        verifyNoInteractions(source);
    }

    @Test
    void absentTwoOfThreeOrExpiredRowsAreNotReconstructedFromDailyCandles(){
        allow(originalRows().subList(0,2));
        assertEquals("STR179_THREE_ORIGINAL_QUOTE_EVENTS_MISSING",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reasonCode());
        allow(List.of());
        assertEquals("STR179_THREE_ORIGINAL_QUOTE_EVENTS_MISSING",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reasonCode());
    }

    @Test
    void futureObservedAndDuplicateExchangeTicksAreBlocked(){
        var rows=new ArrayList<>(originalRows());
        var last=rows.getLast();var old=last.snapshot();
        var future=new FnoVerifiedIntradayLiquidityStore.Snapshot(
            old.provider(),old.instrumentKey(),old.underlyingKey(),
            old.expiry(),old.lotSize(),old.kind(),old.bid(),old.ask(),
            old.bidDepthUnits(),old.askDepthUnits(),old.oiUnits(),
            old.cumulativeVolumeUnits(),old.exchangeAt(),
            AS_OF.plusSeconds(1),old.oiFirstObservedAt(),old.volumeFirstObservedAt());
        rows.set(2,new FnoVerifiedIntradayLiquidityStore.ReplayProof(
            future,HASH,HASH,HASH,HASH,HASH));
        allow(rows);
        assertEquals("STR179_ORIGINAL_SOURCE_CUSTODY_OR_SEQUENCE_INVALID",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reasonCode());
        var duplicates=new ArrayList<>(originalRows());
        duplicates.set(1,duplicates.get(0));
        allow(duplicates);
        assertEquals("STR179_ORIGINAL_SOURCE_CUSTODY_OR_SEQUENCE_INVALID",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reasonCode());
    }

    @Test
    void malformedReceiptAndChangedProviderCannotMasqueradeAsOriginal(){
        var rows=new ArrayList<>(originalRows());
        rows.set(1,row(20,"UPSTOX_V3","unsigned"));
        allow(rows);
        assertEquals("STR179_ORIGINAL_SOURCE_CUSTODY_OR_SEQUENCE_INVALID",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reasonCode());
        rows.set(1,row(20,"ANGEL_ONE_SMARTAPI",HASH));
        allow(rows);
        assertEquals("STR179_ORIGINAL_SOURCE_CUSTODY_OR_SEQUENCE_INVALID",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reasonCode());
    }

    @Test
    void sqlFailureCannotReturnUpstreamQuoteOrCredential(){
        allow(originalRows());
        when(source.latestThreeReplayFutures(KEY,EXPIRY,25,AS_OF))
            .thenThrow(new IllegalStateException("private-password bearer:secret"));
        var r=service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF);
        assertEquals("STR179_SOURCE_READ_UNAVAILABLE",r.reasonCode());
        assertFalse(r.toString().contains("secret"));
        assertNull(r.sourceInputDigest());
    }
}
