package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import in.aitradingsystem.marketdata.FnoVerifiedExpiryBasisPairStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fictional source pair fixtures only; real market outcomes remain 0/43. */
class Fno180VerifiedHistoricalReplayServiceTest {
    private static final String SPOT="NSE_EQ|INE002A01018";
    private static final String FUT="NSE_FO|DEC_180";
    private static final int LOT=250;
    private static final LocalDate EXPIRY=LocalDate.of(2026,11,27);
    private static final Instant AS_OF=Instant.parse("2026-11-20T11:00:00Z");
    private static final Clock CLOCK=Clock.fixed(
        Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC);
    private static final LocalDate[] DATES={
        LocalDate.of(2026,11,13),LocalDate.of(2026,11,16),
        LocalDate.of(2026,11,17),LocalDate.of(2026,11,18),
        LocalDate.of(2026,11,19)
    };
    private static final String CONTRACT_HASH="a".repeat(64);
    private static final String COST_HASH="b".repeat(64);
    private static final FnoExpiryBasisReversionV1.Assumptions ASSUMPTIONS=
        new FnoExpiryBasisReversionV1.Assumptions(
            n("500"),n("0"),n("20"),3,150,30,50,2500,
            true,true,true,true);

    private final FnoVerifiedExpiryBasisPairStore store=
        mock(FnoVerifiedExpiryBasisPairStore.class);
    private final MarketSessionCalendar calendar=mock(MarketSessionCalendar.class);
    private static BigDecimal n(String v){return new BigDecimal(v);}
    private Fno180VerifiedHistoricalReplayService service(boolean a,boolean b,boolean c){
        return new Fno180VerifiedHistoricalReplayService(
            store,calendar,CLOCK,a,b,c);
    }
    private static FnoVerifiedExpiryBasisPairStore.PairProof proof(
        int i,String provider,Instant spotFirst) {
        var d=DATES[i];
        var cashClose=FnoExpiryBasisReversionV1.cashClose(d);
        var futuresClose=FnoExpiryBasisReversionV1.futuresClose(d);
        var future=n(new String[]{"104","103.2","102.6","101.8","100.85"}[i]);
        return new FnoVerifiedExpiryBasisPairStore.PairProof(
            provider,SPOT,FUT,LocalDate.of(2026,11,1),
            EXPIRY,LOT,d,n("99.99"),n("100.01"),
            future.subtract(n("0.01")),future.add(n("0.01")),
            cashClose.minusSeconds(45),cashClose.minusSeconds(30),
            spotFirst,cashClose.plusSeconds(30),
            cashClose.plusSeconds(60),futuresClose.plusSeconds(80),
            cashClose,futuresClose,true,CONTRACT_HASH,COST_HASH,ASSUMPTIONS);
    }
    private static List<FnoVerifiedExpiryBasisPairStore.PairProof> rows(){
        var result=new ArrayList<FnoVerifiedExpiryBasisPairStore.PairProof>();
        for(int i=0;i<5;i++){
            var cashClose=FnoExpiryBasisReversionV1.cashClose(DATES[i]);
            result.add(proof(i,"UPSTOX_ANALYTICS",cashClose.plusSeconds(30)));
        }
        return result;
    }
    private void ready(List<FnoVerifiedExpiryBasisPairStore.PairProof> pairs){
        when(store.readExactPair(eq(SPOT),eq(FUT),eq(EXPIRY),eq(LOT),
            any(),any(),eq(AS_OF))).thenReturn(pairs);
        when(calendar.isTradingDay(any(LocalDate.class))).thenAnswer(call->{
            LocalDate d=call.getArgument(0);
            return d.getDayOfWeek()!=DayOfWeek.SATURDAY&&
                d.getDayOfWeek()!=DayOfWeek.SUNDAY;
        });
    }

    @Test void deniesMissingRightsWithoutReadingPrivatePairedQuotes(){
        for(int denied=0;denied<3;denied++){
            boolean[] rights={true,true,true};rights[denied]=false;
            var result=service(rights[0],rights[1],rights[2])
                .inspect(SPOT,FUT,EXPIRY,LOT,AS_OF);
            assertEquals("STR180_SPOT_FUTURE_CARRY_RIGHTS_UNVERIFIED",result.reason());
            assertEquals(0,result.evaluatedPairs());
            assertFalse(result.historicalE2EAccepted());
        }
        verifyNoInteractions(store,calendar);
    }

    @Test void authenticShapeInvokesActualExpiryBasisEvaluatorResearchOnly(){
        ready(rows());
        var report=service(true,true,true).inspect(SPOT,FUT,EXPIRY,LOT,AS_OF);
        assertEquals("ORIGINAL_PAIR_EVALUATOR_RESEARCH_ONLY",report.state());
        assertEquals("EXPIRY_BASIS_ABOVE_FAIR_CONVERGENCE_OBSERVED",report.reason());
        assertEquals(5,report.sourcePairs());
        assertEquals(5,report.evaluatedPairs());
        assertFalse(report.historicalE2EAccepted());
        assertFalse(report.paperLedgerE2E());
        assertFalse(report.liveBrokerE2E());
        assertFalse(report.ordersPlaced());
        verify(store).readExactPair(eq(SPOT),eq(FUT),eq(EXPIRY),eq(LOT),
            any(),any(),eq(AS_OF));
    }

    @Test void missingOrMixedProviderAndDuplicateOriginalPairsBlock(){
        ready(rows().subList(0,4));
        assertEquals("STR180_FIVE_ORIGINAL_PAIRS_MISSING",
            service(true,true,true).inspect(SPOT,FUT,EXPIRY,LOT,AS_OF).reason());
        var mixed=rows();
        var changed=mixed.getLast();
        mixed.set(4,new FnoVerifiedExpiryBasisPairStore.PairProof(
            "ANGEL_ONE_SMARTAPI",changed.spotKey(),changed.futuresKey(),
            changed.listedFrom(),changed.expiry(),changed.lotSize(),
            changed.sessionDate(),changed.spotBid(),changed.spotAsk(),
            changed.futuresBid(),changed.futuresAsk(),changed.spotQuoteAt(),
            changed.futuresQuoteAt(),changed.spotQuoteFirstObservedAt(),
            changed.futuresQuoteFirstObservedAt(),
            changed.spotFinalFirstAvailableAt(),
            changed.futuresFinalFirstAvailableAt(),
            changed.cashSessionCloseAt(),changed.futuresSessionCloseAt(),
            changed.deliverableSpotVerified(),
            changed.contractSourceDigest(),changed.carryCostSourceDigest(),
            changed.assumptions()));
        ready(mixed);
        assertEquals("STR180_ORIGINAL_PAIR_CUSTODY_OR_IDENTITY_INVALID",
            service(true,true,true).inspect(SPOT,FUT,EXPIRY,LOT,AS_OF).reason());
        var duplicate=rows();
        duplicate.set(4,duplicate.get(3));
        ready(duplicate);
        assertEquals("STR180_ORIGINAL_PAIR_CUSTODY_OR_IDENTITY_INVALID",
            service(true,true,true).inspect(SPOT,FUT,EXPIRY,LOT,AS_OF).reason());
    }

    @Test void futureKnownFinalValueAndCalendarGapReject(){
        var leaked=rows();
        var entry=leaked.getLast();
        leaked.set(4,new FnoVerifiedExpiryBasisPairStore.PairProof(
            entry.provider(),entry.spotKey(),entry.futuresKey(),
            entry.listedFrom(),entry.expiry(),entry.lotSize(),entry.sessionDate(),
            entry.spotBid(),entry.spotAsk(),entry.futuresBid(),entry.futuresAsk(),
            entry.spotQuoteAt(),entry.futuresQuoteAt(),
            entry.spotQuoteFirstObservedAt(),entry.futuresQuoteFirstObservedAt(),
            AS_OF.plusSeconds(1),entry.futuresFinalFirstAvailableAt(),
            entry.cashSessionCloseAt(),entry.futuresSessionCloseAt(),
            entry.deliverableSpotVerified(),entry.contractSourceDigest(),
            entry.carryCostSourceDigest(),entry.assumptions()));
        ready(leaked);
        assertEquals("STR180_ORIGINAL_PAIR_CUSTODY_OR_IDENTITY_INVALID",
            service(true,true,true).inspect(SPOT,FUT,EXPIRY,LOT,AS_OF).reason());
        ready(rows());
        when(calendar.isTradingDay(LocalDate.of(2026,11,14))).thenReturn(true);
        assertEquals("STR180_HISTORICAL_EXCHANGE_SESSION_GAP",
            service(true,true,true).inspect(SPOT,FUT,EXPIRY,LOT,AS_OF).reason());
    }

    @Test void invalidContractScopeNeverRunsSqlAndExceptionIsSanitized(){
        var service=service(true,true,true);
        assertEquals("STR180_REPLAY_CONTRACT_OR_SCOPE_INVALID",
            service.inspect("NSE_INDEX|Nifty 50",FUT,EXPIRY,LOT,AS_OF).reason());
        assertEquals("STR180_REPLAY_CONTRACT_OR_SCOPE_INVALID",
            service.inspect(SPOT,FUT,EXPIRY,LOT,CLOCK.instant().plusSeconds(1)).reason());
        verifyNoInteractions(store);
        when(store.readExactPair(eq(SPOT),eq(FUT),eq(EXPIRY),eq(LOT),
            any(),any(),eq(AS_OF)))
            .thenThrow(new IllegalArgumentException("provider secret token"));
        var result=service.inspect(SPOT,FUT,EXPIRY,LOT,AS_OF);
        assertEquals("STR180_ORIGINAL_PAIR_SOURCE_UNAVAILABLE",result.reason());
        assertFalse(result.toString().contains("token"));
    }
}
