package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoVerifiedExpiryBasisPairStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Synthetic five sessions test ORIGINAL STR-180 evaluator, not real market E2E. */
class Fno180VerifiedHistoricalReplayServiceTest {
    private static final String SPOT="NSE_EQ|INE002A01018";
    private static final String FUTURE="NSE_FO|DEC_180";
    private static final LocalDate EXPIRY=LocalDate.of(2026,11,27);
    private static final Instant AS_OF=Instant.parse("2026-11-20T11:00:00Z");
    private static final Clock CLOCK=Clock.fixed(
        Instant.parse("2026-11-22T05:00:00Z"),ZoneOffset.UTC);
    private static final LocalDate[] DATES={
        LocalDate.of(2026,11,13),LocalDate.of(2026,11,16),
        LocalDate.of(2026,11,17),LocalDate.of(2026,11,18),
        LocalDate.of(2026,11,19)
    };
    private static final String[] FUTURES={
        "104","103.2","102.6","101.8","100.85"
    };
    private static final String HASH="a".repeat(64);
    private final FnoVerifiedExpiryBasisPairStore source=
        mock(FnoVerifiedExpiryBasisPairStore.class);
    private final in.aitradingsystem.marketdata.MarketSessionCalendar calendar=
        mock(in.aitradingsystem.marketdata.MarketSessionCalendar.class);

    private Fno180VerifiedHistoricalReplayService service(
            boolean research,boolean basis,boolean discovery) {
        return new Fno180VerifiedHistoricalReplayService(
            source,calendar,CLOCK,research,basis,discovery);
    }
    private static BigDecimal n(String s){return new BigDecimal(s);}
    private static FnoExpiryBasisReversionV1.Assumptions assumptions() {
        return new FnoExpiryBasisReversionV1.Assumptions(
            n("500"),n("0"),n("20"),3,150,30,50,2500,true,true,true,true);
    }
    private static FnoVerifiedExpiryBasisPairStore.PairProof one(int index) {
        LocalDate day=DATES[index];
        Instant close=FnoExpiryBasisReversionV1.cashClose(day);
        Instant fClose=FnoExpiryBasisReversionV1.futuresClose(day);
        BigDecimal spot=n("100");
        BigDecimal future=n(FUTURES[index]);
        return new FnoVerifiedExpiryBasisPairStore.PairProof(
            "UPSTOX_ANALYTICS",SPOT,FUTURE,LocalDate.of(2026,11,1),
            EXPIRY,250,day,spot.subtract(n(".01")),spot.add(n(".01")),
            future.subtract(n(".01")),future.add(n(".01")),
            close.minusSeconds(45),close.minusSeconds(30),
            close.plusSeconds(30),close.plusSeconds(30),
            close.plusSeconds(60),fClose.plusSeconds(80),
            close,fClose,true,HASH,"b".repeat(64),assumptions());
    }
    private static List<FnoVerifiedExpiryBasisPairStore.PairProof> five(){
        var data=new ArrayList<FnoVerifiedExpiryBasisPairStore.PairProof>();
        for(int i=0;i<5;i++)data.add(one(i));
        return List.copyOf(data);
    }
    private void allow(List<FnoVerifiedExpiryBasisPairStore.PairProof> list) {
        when(source.readExactPair(eq(SPOT),eq(FUTURE),eq(EXPIRY),eq(250),
            any(),any(),eq(AS_OF))).thenReturn(list);
        when(calendar.isTradingDay(any(LocalDate.class))).thenAnswer(inv->{
            LocalDate day=inv.getArgument(0);
            return day.getDayOfWeek()!=DayOfWeek.SATURDAY
                && day.getDayOfWeek()!=DayOfWeek.SUNDAY;
        });
    }

    @Test void originalPairedHistoricalSourceInvokesActualBasisConvergenceEvaluator(){
        allow(five());
        var result=service(true,true,true).inspect(SPOT,FUTURE,EXPIRY,250,AS_OF);
        assertEquals("ORIGINAL_PAIR_EVALUATOR_RESEARCH_ONLY",result.state());
        assertEquals("RESEARCH_CANDIDATE",result.evaluatorStatus());
        assertEquals("EXPIRY_BASIS_ABOVE_FAIR_CONVERGENCE_OBSERVED",
            result.evaluatorReasonCode());
        assertEquals(5,result.originalPairedSourceRows());
        assertEquals(5,result.verifiedPairedSessions());
        assertEquals(64,result.privateDerivedInputSha256().length());
        assertFalse(result.acceptedHistoricalE2E());
        assertFalse(result.paperLedgerE2E());
        assertFalse(result.liveBrokerE2E());
        assertFalse(result.ordersPlaced());
        assertFalse(result.toString().contains(SPOT));
        assertFalse(result.toString().contains(FUTURE));
    }

    @Test void anyMissingIndependentRightStopsBeforeAnySqlCall(){
        for(int i=0;i<3;i++){
            var rights=new boolean[]{true,true,true};rights[i]=false;
            var result=service(rights[0],rights[1],rights[2])
                .inspect(SPOT,FUTURE,EXPIRY,250,AS_OF);
            assertEquals("STR180_SOURCE_CARRY_RIGHTS_OR_DISCOVERY_UNVERIFIED",
                result.evaluatorReasonCode());
            assertNull(result.privateDerivedInputSha256());
        }
        verifyNoInteractions(source,calendar);
    }

    @Test void insufficientSourcePairsAndInvalidScopeBlock(){
        allow(five().subList(0,4));
        assertEquals("STR180_FIVE_ORIGINAL_SESSION_PAIRS_MISSING",
            service(true,true,true).inspect(SPOT,FUTURE,EXPIRY,250,AS_OF)
                .evaluatorReasonCode());
        assertEquals("STR180_HISTORICAL_SCOPE_INVALID",
            service(true,true,true).inspect("NSE_INDEX|Nifty 50",
                FUTURE,EXPIRY,250,AS_OF).evaluatorReasonCode());
        assertEquals("STR180_HISTORICAL_SCOPE_INVALID",
            service(true,true,true).inspect(SPOT,FUTURE,EXPIRY,0,AS_OF)
                .evaluatorReasonCode());
        assertEquals("STR180_EXPIRY_OUTSIDE_RESEARCH_WINDOW",
            service(true,true,true).inspect(SPOT,FUTURE,LocalDate.of(2026,12,30),
                250,AS_OF).evaluatorReasonCode());
    }

    @Test void sourceFirstKnownAfterDecisionNeverMakesRetroactiveSignal(){
        var rows=new ArrayList<>(five());
        var p=rows.getLast();
        rows.set(4,new FnoVerifiedExpiryBasisPairStore.PairProof(
            p.provider(),p.spotKey(),p.futuresKey(),p.listedFrom(),
            p.expiry(),p.lotSize(),p.sessionDate(),
            p.spotBid(),p.spotAsk(),p.futuresBid(),p.futuresAsk(),
            p.spotQuoteAt(),p.futuresQuoteAt(),
            p.spotQuoteFirstObservedAt(),p.futuresQuoteFirstObservedAt(),
            p.spotFinalFirstAvailableAt(),AS_OF.plusSeconds(1),
            p.cashSessionCloseAt(),p.futuresSessionCloseAt(),
            p.deliverableSpotVerified(),p.contractSourceDigest(),
            p.carryCostSourceDigest(),p.assumptions()));
        allow(rows);
        assertEquals("STR180_ORIGINAL_PAIR_PROVENANCE_OR_CARRY_INVALID",
            service(true,true,true).inspect(SPOT,FUTURE,EXPIRY,250,AS_OF)
                .evaluatorReasonCode());
    }

    @Test void originalCarryHashAndVerifiedSessionCannotChangeMidReplay(){
        var rows=new ArrayList<>(five());var p=rows.get(2);
        rows.set(2,new FnoVerifiedExpiryBasisPairStore.PairProof(
            p.provider(),p.spotKey(),p.futuresKey(),p.listedFrom(),p.expiry(),
            p.lotSize(),p.sessionDate(),p.spotBid(),p.spotAsk(),p.futuresBid(),
            p.futuresAsk(),p.spotQuoteAt(),p.futuresQuoteAt(),
            p.spotQuoteFirstObservedAt(),p.futuresQuoteFirstObservedAt(),
            p.spotFinalFirstAvailableAt(),p.futuresFinalFirstAvailableAt(),
            p.cashSessionCloseAt(),p.futuresSessionCloseAt(),
            true,HASH,"bad_hash",p.assumptions()));
        allow(rows);
        assertEquals("STR180_ORIGINAL_PAIR_PROVENANCE_OR_CARRY_INVALID",
            service(true,true,true).inspect(SPOT,FUTURE,EXPIRY,250,AS_OF)
                .evaluatorReasonCode());
    }

    @Test void duplicateSessionAndUnavailableSourceFailClosedWithoutQuotes(){
        var rows=new ArrayList<>(five());
        rows.set(3,rows.get(2));
        allow(rows);
        assertEquals("STR180_ORIGINAL_PAIR_PROVENANCE_OR_CARRY_INVALID",
            service(true,true,true).inspect(SPOT,FUTURE,EXPIRY,250,AS_OF)
                .evaluatorReasonCode());
        when(source.readExactPair(eq(SPOT),eq(FUTURE),eq(EXPIRY),eq(250),
            any(),any(),eq(AS_OF))).thenThrow(
                new IllegalStateException("bearer_secret_in_database_message"));
        var result=service(true,true,true).inspect(SPOT,FUTURE,EXPIRY,250,AS_OF);
        assertEquals("STR180_ORIGINAL_SOURCE_UNAVAILABLE",
            result.evaluatorReasonCode());
        assertFalse(result.toString().contains("bearer_secret"));
    }
}
