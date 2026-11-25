package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.Fno170VerifiedIntradayPairStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Synthetic L1 fixtures do not constitute genuine historical market E2E. */
class Fno170VerifiedHistoricalReplayServiceTest {
    private static final String CASH="NSE_EQ|INE002A01018";
    private static final String FUT="NSE_FO|DEC_170";
    private static final LocalDate EXP=LocalDate.of(2026,12,24);
    private static final Instant ASOF=Instant.parse("2026-11-20T05:00:00Z");
    private static final Clock CLOCK=Clock.fixed(
        Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC);
    private static final String HASH="a".repeat(64);
    private final Fno170VerifiedIntradayPairStore source=
        mock(Fno170VerifiedIntradayPairStore.class);
    private final MarketSessionCalendar calendar=
        mock(MarketSessionCalendar.class);

    private Fno170VerifiedHistoricalReplayService svc(
            boolean research,boolean intraday,boolean discovery) {
        return new Fno170VerifiedHistoricalReplayService(
            source,calendar,CLOCK,research,intraday,discovery);
    }

    private static BigDecimal n(String s){return new BigDecimal(s);}
    private static Fno170VerifiedIntradayPairStore.Pair pair(
            Instant stockAt,Instant futureAt,Instant stockKnown,
            Instant futureKnown,String spotHash) {
        return new Fno170VerifiedIntradayPairStore.Pair(
            "UPSTOX_V3",CASH,FUT,LocalDate.of(2026,11,1),EXP,250,
            n("100.00"),n("100.10"),n("103.50"),n("103.70"),
            stockAt,futureAt,stockKnown,futureKnown,
            n("600"),n("0"),n("20"),n("25"),
            spotHash,HASH,HASH,HASH,true,true);
    }
    private static Fno170VerifiedIntradayPairStore.Pair good() {
        return pair(ASOF.minusSeconds(2),ASOF.minusSeconds(2),
            ASOF.minusSeconds(1),ASOF.minusSeconds(1),HASH);
    }
    private void enabled(Fno170VerifiedIntradayPairStore.Pair data){
        when(calendar.isInsideCashSession(ASOF)).thenReturn(true);
        when(calendar.isInsideFnoSession(ASOF)).thenReturn(true);
        when(source.readExactPair(CASH,FUT,EXP,250,ASOF))
            .thenReturn(data==null?List.of():List.of(data));
    }

    @Test void originalRightsAttestedL1RunsActualCashCarryEvaluatorWithoutOrders(){
        enabled(good());
        var report=svc(true,true,true).inspect(CASH,FUT,EXP,250,ASOF);
        assertEquals("ORIGINAL_INTRADAY_RESEARCH_ONLY",report.state());
        assertEquals("RESEARCH_CANDIDATE",report.evaluatorStatus());
        assertEquals("NET_CASH_CARRY_RESEARCH_EDGE",report.reasonCode());
        assertEquals(1,report.originalPairedObservations());
        assertEquals(1,report.evaluatorCalls());
        assertEquals(64,report.localSourceInputSha256().length());
        assertFalse(report.independentHistoricalE2E());
        assertFalse(report.paperLedgerE2E());
        assertFalse(report.liveBrokerE2E());
        assertFalse(report.ordersPlaced());
        assertFalse(report.toString().contains(CASH));
        assertFalse(report.toString().contains(FUT));
        assertFalse(report.toString().contains("103.50"));
        verify(source).readExactPair(CASH,FUT,EXP,250,ASOF);
    }

    @Test void missingAnyIndependentRightDeniesBeforeSqlOrCalendar(){
        for(int denied=0;denied<3;denied++){
            boolean[] f={true,true,true};f[denied]=false;
            var result=svc(f[0],f[1],f[2])
                .inspect(CASH,FUT,EXP,250,ASOF);
            assertEquals("STR170_SOURCE_RIGHTS_OR_LISTING_NOT_VERIFIED",
                result.reasonCode());
        }
        verifyNoInteractions(calendar,source);
    }

    @Test void V105AfterCloseFinalizedQuoteCannotSatisfyFifteenSecondOriginalAsOf(){
        enabled(pair(ASOF.minusSeconds(100),ASOF.minusSeconds(100),
            ASOF.minusSeconds(90),ASOF.minusSeconds(90),HASH));
        assertEquals("STR170_ORIGINAL_INSTRUMENT_QUOTE_OR_COST_PROVENANCE_INVALID",
            svc(true,true,true).inspect(CASH,FUT,EXP,250,ASOF).reasonCode());
        enabled(pair(ASOF.minusSeconds(2),ASOF.minusSeconds(2),
            ASOF.plusSeconds(1),ASOF.minusSeconds(1),HASH));
        assertEquals("STR170_ORIGINAL_INSTRUMENT_QUOTE_OR_COST_PROVENANCE_INVALID",
            svc(true,true,true).inspect(CASH,FUT,EXP,250,ASOF).reasonCode());
    }

    @Test void forgedHashAndMissingIndependentSpotOrFutureSourceBlocks(){
        enabled(pair(ASOF.minusSeconds(2),ASOF.minusSeconds(2),
            ASOF.minusSeconds(1),ASOF.minusSeconds(1),"not_original_hash"));
        assertEquals("STR170_ORIGINAL_INSTRUMENT_QUOTE_OR_COST_PROVENANCE_INVALID",
            svc(true,true,true).inspect(CASH,FUT,EXP,250,ASOF).reasonCode());
        enabled(null);
        assertEquals("STR170_ONE_ORIGINAL_INTRADAY_PAIR_MISSING",
            svc(true,true,true).inspect(CASH,FUT,EXP,250,ASOF).reasonCode());
    }

    @Test void invalidScopeOrOutsideNseCashFuturesOverlapBlocksBeforeSql(){
        assertEquals("STR170_CASH_AND_FO_SESSION_NOT_VERIFIED",
            svc(true,true,true).inspect(CASH,FUT,EXP,250,ASOF).reasonCode());
        assertEquals("STR170_REPLAY_QUERY_SCOPE_INVALID",
            svc(true,true,true).inspect("NSE_INDEX|Nifty 50",
                FUT,EXP,250,ASOF).reasonCode());
        assertEquals("STR170_REPLAY_QUERY_SCOPE_INVALID",
            svc(true,true,true).inspect(CASH,FUT,EXP,0,ASOF).reasonCode());
        assertEquals("STR170_REPLAY_QUERY_SCOPE_INVALID",
            svc(true,true,true).inspect(CASH,FUT,EXP,250,CLOCK.instant().plusSeconds(1))
                .reasonCode());
        verifyNoInteractions(source);
    }

    @Test void sourceExceptionIsRedactedAndNeverCreatesArtificialResearch(){
        enabled(good());
        when(source.readExactPair(CASH,FUT,EXP,250,ASOF))
            .thenThrow(new IllegalStateException("secret=true price=250000"));
        var result=svc(true,true,true).inspect(CASH,FUT,EXP,250,ASOF);
        assertEquals("STR170_ORIGINAL_INTRADAY_PAIR_UNAVAILABLE",result.reasonCode());
        assertNull(result.localSourceInputSha256());
        assertFalse(result.toString().contains("secret"));
    }
}
