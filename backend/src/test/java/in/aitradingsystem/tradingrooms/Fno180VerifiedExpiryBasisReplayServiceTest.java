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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Synthetic V105 objects; evaluator itself is unchanged original STR-180 Java. */
class Fno180VerifiedExpiryBasisReplayServiceTest {
    private static final String SPOT="NSE_EQ|INE002A01018";
    private static final String FUT="NSE_FO|DEC_180";
    private static final LocalDate EXPIRY=LocalDate.of(2026,11,27);
    private static final Instant AS_OF=Instant.parse("2026-11-20T11:00:00Z");
    private static final Clock CLOCK=Clock.fixed(
        Instant.parse("2026-11-22T06:00:00Z"),ZoneOffset.UTC);
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final LocalDate[] DAYS={
        LocalDate.of(2026,11,13),LocalDate.of(2026,11,16),
        LocalDate.of(2026,11,17),LocalDate.of(2026,11,18),
        LocalDate.of(2026,11,19)};
    private static final String[] FUTURE_MIDS={
        "104","103.2","102.6","101.8","100.85"};
    private static final String CONTRACT_HASH="a".repeat(64);
    private static final String COST_HASH="b".repeat(64);
    private static final FnoExpiryBasisReversionV1.Assumptions ASSUMPTIONS=
        new FnoExpiryBasisReversionV1.Assumptions(
            new BigDecimal("500"),BigDecimal.ZERO,
            new BigDecimal("20"),3,150,30,50,2500,
            true,true,true,true);

    private final FnoVerifiedExpiryBasisPairStore store=
        mock(FnoVerifiedExpiryBasisPairStore.class);
    private final MarketSessionCalendar calendar=
        mock(MarketSessionCalendar.class);

    private Fno180VerifiedExpiryBasisReplayService service(
            boolean research,boolean basis,boolean discovery) {
        return new Fno180VerifiedExpiryBasisReplayService(
            store,calendar,CLOCK,research,basis,discovery);
    }
    private static Instant cashClose(LocalDate day){
        return day.atTime(LocalTime.of(15,30)).atZone(IST).toInstant();
    }
    private static Instant futuresClose(LocalDate day){
        return day.atTime(LocalTime.of(15,40)).atZone(IST).toInstant();
    }
    private static FnoVerifiedExpiryBasisPairStore.PairProof original(int idx){
        var day=DAYS[idx];
        var close=cashClose(day);
        var future=new BigDecimal(FUTURE_MIDS[idx]);
        return new FnoVerifiedExpiryBasisPairStore.PairProof(
            "UPSTOX_ANALYTICS",SPOT,FUT,LocalDate.of(2026,11,1),
            EXPIRY,250,day,new BigDecimal("99.99"),
            new BigDecimal("100.01"),
            future.subtract(new BigDecimal(".01")),
            future.add(new BigDecimal(".01")),
            close.minusSeconds(45),close.minusSeconds(30),
            close.plusSeconds(30),close.plusSeconds(30),
            close.plusSeconds(60),futuresClose(day).plusSeconds(80),
            close,futuresClose(day),
            true,CONTRACT_HASH,COST_HASH,ASSUMPTIONS);
    }
    private static List<FnoVerifiedExpiryBasisPairStore.PairProof> five(){
        var all=new ArrayList<FnoVerifiedExpiryBasisPairStore.PairProof>();
        for(int i=0;i<5;i++)all.add(original(i));
        return List.copyOf(all);
    }
    private void data(List<FnoVerifiedExpiryBasisPairStore.PairProof> rows){
        when(store.readExactPair(eq(SPOT),eq(FUT),eq(EXPIRY),
            eq(250),any(),any(),eq(AS_OF))).thenReturn(rows);
        when(calendar.isTradingDay(any(LocalDate.class))).thenAnswer(inv->{
            LocalDate d=inv.getArgument(0);
            return d.getDayOfWeek()!=DayOfWeek.SATURDAY
                && d.getDayOfWeek()!=DayOfWeek.SUNDAY;
        });
    }

    @Test void missingEachIndependentRightRejectsBeforeSourceRead(){
        for(int deny=0;deny<3;deny++){
            boolean[] flags={true,true,true};
            flags[deny]=false;
            var result=service(flags[0],flags[1],flags[2])
                .inspect(SPOT,FUT,EXPIRY,250,AS_OF);
            assertEquals("STR180_EQUITY_FUTURES_CARRY_RIGHTS_UNVERIFIED",
                result.reasonCode());
            assertFalse(result.historicalMarketE2E());
        }
        verifyNoInteractions(store,calendar);
    }

    @Test void originalCashFuturesFivePairsCallRealJavaBasisEvaluator(){
        data(five());
        var result=service(true,true,true)
            .inspect(SPOT,FUT,EXPIRY,250,AS_OF);
        assertEquals("ORIGINAL_PAIR_EVALUATOR_RESEARCH_ONLY",result.state());
        assertEquals("RESEARCH_CANDIDATE",result.evaluatorStatus());
        assertEquals("EXPIRY_BASIS_ABOVE_FAIR_CONVERGENCE_OBSERVED",
            result.reasonCode());
        assertEquals(5,result.originalPairedSessions());
        assertEquals(5,result.completedSessions());
        assertFalse(result.historicalMarketE2E());
        assertFalse(result.paperLedgerE2E());
        assertFalse(result.liveBrokerE2E());
        assertFalse(result.ordersPlaced());
        verify(store).readExactPair(eq(SPOT),eq(FUT),eq(EXPIRY),
            eq(250),any(),any(),eq(AS_OF));
    }

    @Test void sourceKnownAfterAsOfOrForgedCloseTimesNeverReplays(){
        var x=new ArrayList<>(five());
        var r=x.getLast();
        x.set(4,new FnoVerifiedExpiryBasisPairStore.PairProof(
            r.provider(),r.spotKey(),r.futuresKey(),r.listedFrom(),r.expiry(),
            r.lotSize(),r.sessionDate(),r.spotBid(),r.spotAsk(),
            r.futuresBid(),r.futuresAsk(),r.spotQuoteAt(),r.futuresQuoteAt(),
            r.spotQuoteFirstObservedAt(),r.futuresQuoteFirstObservedAt(),
            r.spotFinalFirstAvailableAt(),AS_OF.plusSeconds(1),
            r.cashSessionCloseAt(),r.futuresSessionCloseAt(),
            r.deliverableSpotVerified(),r.contractSourceDigest(),
            r.carryCostSourceDigest(),r.assumptions()));
        data(x);
        assertEquals("STR180_PAIRED_ORIGINAL_SOURCE_CUSTODY_INVALID",
            service(true,true,true).inspect(SPOT,FUT,EXPIRY,250,AS_OF).reasonCode());
        var t=five().getFirst();
        x=new ArrayList<>(five());
        x.set(0,new FnoVerifiedExpiryBasisPairStore.PairProof(
            t.provider(),t.spotKey(),t.futuresKey(),t.listedFrom(),t.expiry(),
            t.lotSize(),t.sessionDate(),t.spotBid(),t.spotAsk(),
            t.futuresBid(),t.futuresAsk(),t.spotQuoteAt(),t.futuresQuoteAt(),
            t.spotQuoteFirstObservedAt(),t.futuresQuoteFirstObservedAt(),
            t.spotFinalFirstAvailableAt(),t.futuresFinalFirstAvailableAt(),
            t.cashSessionCloseAt(),t.cashSessionCloseAt(),
            t.deliverableSpotVerified(),t.contractSourceDigest(),
            t.carryCostSourceDigest(),t.assumptions()));
        data(x);
        assertEquals("STR180_PAIRED_ORIGINAL_SOURCE_CUSTODY_INVALID",
            service(true,true,true).inspect(SPOT,FUT,EXPIRY,250,AS_OF).reasonCode());
    }

    @Test void tamperedContractDigestOrChangedCarryModelCannotMixSessions(){
        var x=new ArrayList<>(five());
        var r=x.getLast();
        x.set(4,new FnoVerifiedExpiryBasisPairStore.PairProof(
            r.provider(),r.spotKey(),r.futuresKey(),r.listedFrom(),r.expiry(),
            r.lotSize(),r.sessionDate(),r.spotBid(),r.spotAsk(),
            r.futuresBid(),r.futuresAsk(),r.spotQuoteAt(),r.futuresQuoteAt(),
            r.spotQuoteFirstObservedAt(),r.futuresQuoteFirstObservedAt(),
            r.spotFinalFirstAvailableAt(),r.futuresFinalFirstAvailableAt(),
            r.cashSessionCloseAt(),r.futuresSessionCloseAt(),
            r.deliverableSpotVerified(),"not_a_sha256",
            r.carryCostSourceDigest(),r.assumptions()));
        data(x);
        assertEquals("STR180_PAIRED_ORIGINAL_SOURCE_CUSTODY_INVALID",
            service(true,true,true).inspect(SPOT,FUT,EXPIRY,250,AS_OF).reasonCode());
    }

    @Test void missingSourceDuplicateOrSourceFailureReturnsSanitizedBlock(){
        data(five().subList(0,4));
        assertEquals("STR180_FIVE_ORIGINAL_EQ_FO_PAIRS_MISSING",
            service(true,true,true).inspect(SPOT,FUT,EXPIRY,250,AS_OF).reasonCode());
        var duplicated=new ArrayList<>(five());
        duplicated.set(4,duplicated.get(3));
        data(duplicated);
        assertEquals("STR180_PAIRED_ORIGINAL_SOURCE_CUSTODY_INVALID",
            service(true,true,true).inspect(SPOT,FUT,EXPIRY,250,AS_OF).reasonCode());

        when(store.readExactPair(eq(SPOT),eq(FUT),eq(EXPIRY),
            eq(250),any(),any(),eq(AS_OF)))
            .thenThrow(new IllegalStateException("private bearer token"));
        var result=service(true,true,true).inspect(SPOT,FUT,EXPIRY,250,AS_OF);
        assertEquals("STR180_ORIGINAL_EQ_FO_PAIR_SOURCE_UNAVAILABLE",
            result.reasonCode());
        assertFalse(result.toString().contains("bearer token"));
    }

    @Test void invalidIdentityLotExpiryAndFutureAsOfFailBeforeRead(){
        assertEquals("STR180_HISTORICAL_PAIR_QUERY_SCOPE_INVALID",
            service(true,true,true).inspect("NSE_INDEX|Nifty 50",
                FUT,EXPIRY,250,AS_OF).reasonCode());
        assertEquals("STR180_HISTORICAL_PAIR_QUERY_SCOPE_INVALID",
            service(true,true,true).inspect(SPOT,FUT,EXPIRY,0,AS_OF).reasonCode());
        assertEquals("STR180_EXPIRY_BASIS_DELIVERY_WINDOW_INVALID",
            service(true,true,true).inspect(SPOT,FUT,
                LocalDate.of(2026,12,31),250,AS_OF).reasonCode());
        assertEquals("STR180_HISTORICAL_PAIR_QUERY_SCOPE_INVALID",
            service(true,true,true).inspect(SPOT,FUT,EXPIRY,250,
                CLOCK.instant().plusSeconds(1)).reasonCode());
        verifyNoInteractions(store);
    }
}
