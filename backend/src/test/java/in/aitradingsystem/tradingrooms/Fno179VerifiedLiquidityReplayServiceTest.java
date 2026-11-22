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

class Fno179VerifiedLiquidityReplayServiceTest {
    private static final String KEY="NSE_FO|FUT_DEC_179";
    private static final LocalDate EXPIRY=LocalDate.of(2026,12,29);
    private static final Instant AS_OF=Instant.parse("2026-11-23T05:00:00Z");
    private static final Clock CLOCK=Clock.fixed(
        Instant.parse("2026-11-23T05:10:00Z"),ZoneOffset.UTC);
    private final FnoVerifiedIntradayLiquidityStore source=
        mock(FnoVerifiedIntradayLiquidityStore.class);
    private final MarketSessionCalendar calendar=mock(MarketSessionCalendar.class);

    private Fno179VerifiedLiquidityReplayService service(
            boolean research,boolean liquidity,boolean discovery) {
        return new Fno179VerifiedLiquidityReplayService(
            source,calendar,CLOCK,research,liquidity,discovery);
    }

    private static FnoVerifiedIntradayLiquidityStore.Snapshot snapshot(
            int secondsAgo,String bid,String ask,long oi,long volume) {
        var at=AS_OF.minusSeconds(secondsAgo);
        return new FnoVerifiedIntradayLiquidityStore.Snapshot(
            "UPSTOX_V3",KEY,"NSE_INDEX|Nifty 50",EXPIRY,25,
            "FUTURE",new BigDecimal(bid),new BigDecimal(ask),
            100,125,oi,volume,at,at.plusSeconds(1),
            at.minusSeconds(35),at.plusSeconds(1));
    }
    private static List<FnoVerifiedIntradayLiquidityStore.Snapshot> rows(){
        return List.of(
            snapshot(35,"20000","20004",20000,5000),
            snapshot(20,"20001","20005",20000,5050),
            snapshot(5,"20002","20006",20000,5100));
    }
    private void withData(List<FnoVerifiedIntradayLiquidityStore.Snapshot> records) {
        when(calendar.isTradingDay(LocalDate.of(2026,11,23))).thenReturn(true);
        when(source.latestThreeFutures(KEY,EXPIRY,25,AS_OF)).thenReturn(records);
    }

    @Test void absenceOfEachRightBlocksBeforeReadingSource(){
        for(int x=0;x<3;x++){
            boolean[] flags={true,true,true};
            flags[x]=false;
            var report=service(flags[0],flags[1],flags[2])
                .inspect(KEY,EXPIRY,25,AS_OF);
            assertEquals("STR179_RESEARCH_QUOTE_OI_VOLUME_OR_DISCOVERY_RIGHTS_MISSING",
                report.reasonCode());
            assertFalse(report.historicalMarketE2E());
        }
        verifyNoInteractions(source,calendar);
    }

    @Test void originalThreeSourceObservationsInvokeActualLiquidityResearchGate() {
        withData(rows());
        var report=service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF);
        assertEquals("ORIGINAL_L1_EVALUATOR_RESEARCH_ONLY",report.state());
        assertEquals("RESEARCH_PASS",report.evaluatorStatus());
        assertEquals("LIQUIDITY_RESEARCH_SCREEN_PASSED",report.reasonCode());
        assertEquals(3,report.originalObservations());
        assertEquals(3,report.evaluatedObservations());
        assertFalse(report.historicalMarketE2E());
        assertFalse(report.paperLedgerE2E());
        assertFalse(report.liveBrokerE2E());
        assertFalse(report.ordersPlaced());
        verify(source).latestThreeFutures(KEY,EXPIRY,25,AS_OF);
    }

    @Test void missingThirdDistinctOriginalObservationIsNotInvented() {
        withData(rows().subList(0,2));
        var report=service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF);
        assertEquals("STR179_THREE_ORIGINAL_L1_OBSERVATIONS_MISSING",
            report.reasonCode());
        assertEquals(0,report.evaluatedObservations());
    }

    @Test void differentProviderOrRepeatedExchangeTimestampBlocks() {
        var original=rows();
        var bad=new ArrayList<>(original);
        var last=original.getLast();
        bad.set(2,new FnoVerifiedIntradayLiquidityStore.Snapshot(
            "ANGEL_ONE_SMARTAPI",last.instrumentKey(),last.underlyingKey(),
            last.expiry(),last.lotSize(),last.kind(),last.bid(),last.ask(),
            last.bidDepthUnits(),last.askDepthUnits(),last.oiUnits(),
            last.cumulativeVolumeUnits(),last.exchangeAt(),last.observedAt(),
            last.oiFirstObservedAt(),last.volumeFirstObservedAt()));
        withData(bad);
        assertEquals("STR179_ORIGINAL_L1_PROVENANCE_OR_CONTRACT_AMBIGUOUS",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reasonCode());
        bad.set(2,original.get(1));
        withData(bad);
        assertEquals("STR179_ORIGINAL_L1_PROVENANCE_OR_CONTRACT_AMBIGUOUS",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reasonCode());
    }

    @Test void originalQuoteKnownAfterAsOfNeverReplays() {
        var original=rows();
        var bad=new ArrayList<>(original);
        var last=original.getLast();
        bad.set(2,new FnoVerifiedIntradayLiquidityStore.Snapshot(
            last.provider(),last.instrumentKey(),last.underlyingKey(),
            last.expiry(),last.lotSize(),last.kind(),last.bid(),last.ask(),
            last.bidDepthUnits(),last.askDepthUnits(),last.oiUnits(),
            last.cumulativeVolumeUnits(),last.exchangeAt(),
            AS_OF.plusSeconds(1),last.oiFirstObservedAt(),
            last.volumeFirstObservedAt()));
        withData(bad);
        assertEquals("STR179_ORIGINAL_L1_PROVENANCE_OR_CONTRACT_AMBIGUOUS",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reasonCode());
    }

    @Test void marketClosedOrBadContractCannotQuerySource(){
        when(calendar.isTradingDay(LocalDate.of(2026,11,23))).thenReturn(false);
        assertEquals("STR179_NSE_FO_MARKET_SESSION_NOT_VERIFIED",
            service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF).reasonCode());
        assertEquals("STR179_HISTORICAL_L1_QUERY_SCOPE_INVALID",
            service(true,true,true).inspect("NSE_EQ|BAD",EXPIRY,25,AS_OF)
                .reasonCode());
        assertEquals("STR179_HISTORICAL_L1_QUERY_SCOPE_INVALID",
            service(true,true,true).inspect(KEY,EXPIRY,0,AS_OF).reasonCode());
        assertEquals("STR179_HISTORICAL_L1_QUERY_SCOPE_INVALID",
            service(true,true,true).inspect(KEY,EXPIRY,25,CLOCK.instant().plusSeconds(1))
                .reasonCode());
        verifyNoInteractions(source);
    }

    @Test void sourceExceptionMustNeverExposePrivateQuoteOrCredentials(){
        when(calendar.isTradingDay(LocalDate.of(2026,11,23))).thenReturn(true);
        when(source.latestThreeFutures(KEY,EXPIRY,25,AS_OF))
            .thenThrow(new IllegalStateException("private_token_in_quote_source"));
        var report=service(true,true,true).inspect(KEY,EXPIRY,25,AS_OF);
        assertEquals("STR179_ORIGINAL_INTRADAY_L1_SOURCE_UNAVAILABLE",
            report.reasonCode());
        assertFalse(report.toString().contains("private_token"));
    }
}
