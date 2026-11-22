package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoVerifiedCompletedOhlcvOiStore;
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

/**
 * Fictional SQL fixture only; the host V102 historical data may be empty.
 * Tests the original SQL rights gate adapter to the ACTUAL STR-177 evaluator.
 */
class Fno177VerifiedHistoricalReplayServiceTest {
    private static final String KEY = "NSE_FO|FUT_DEC_MR";
    private static final String HASH = "a".repeat(64);
    private static final Instant AS_OF = Instant.parse("2026-11-23T11:00:00Z");
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-11-23T12:00:00Z"),ZoneOffset.UTC);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate[] DAYS = {
        LocalDate.of(2026,11,9), LocalDate.of(2026,11,10),
        LocalDate.of(2026,11,11),LocalDate.of(2026,11,12),
        LocalDate.of(2026,11,13),LocalDate.of(2026,11,16),
        LocalDate.of(2026,11,17),LocalDate.of(2026,11,18),
        LocalDate.of(2026,11,19),LocalDate.of(2026,11,20)
    };
    private static final String[] CLOSES={
        "100","101","99","100","100.5","99.5","101","99","95","96.5"
    };
    private final FnoVerifiedCompletedOhlcvOiStore source =
        mock(FnoVerifiedCompletedOhlcvOiStore.class);
    private final MarketSessionCalendar calendar =
        mock(MarketSessionCalendar.class);

    private Fno177VerifiedHistoricalReplayService service(
            boolean research,boolean history,boolean discovery){
        return new Fno177VerifiedHistoricalReplayService(
            source,calendar,CLOCK,research,history,discovery);
    }

    private static Instant close(LocalDate day) {
        return day.atTime(LocalTime.of(15,40)).atZone(IST).toInstant();
    }
    private static FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar one(
            int index) {
        LocalDate day=DAYS[index];
        BigDecimal value=new BigDecimal(CLOSES[index]);
        var bar=new FnoVerifiedCompletedOhlcvOiStore.VerifiedBar(
            "UPSTOX_ANALYTICS",KEY,"NSE_INDEX|Nifty 50",
            LocalDate.of(2026,11,1),LocalDate.of(2026,12,24),75,
            day,value,value.add(new BigDecimal("0.4")),
            value.subtract(new BigDecimal("0.4")),value,
            index<8?30000L:index==8?45000L:50000L,
            100000L,close(day).plusSeconds(150),
            close(day).plusSeconds(900),close(day));
        return new FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar(
            bar,HASH,HASH,"b".repeat(64));
    }
    private static List<FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar> ten(){
        var list=new ArrayList<FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar>();
        for(int i=0;i<10;i++)list.add(one(i));
        return List.copyOf(list);
    }
    private void withData(
            List<FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar> rows){
        when(source.readExactReplayContract(eq(KEY),any(),any(),eq(AS_OF)))
            .thenReturn(rows);
        when(calendar.isTradingDay(any(LocalDate.class))).thenAnswer(inv->{
            LocalDate d=inv.getArgument(0);
            return d.getDayOfWeek()!=DayOfWeek.SATURDAY
                && d.getDayOfWeek()!=DayOfWeek.SUNDAY;
        });
    }

    @Test
    void absentIndependentRightNeverReadsHistoricalDatabase() {
        for(int denied=0;denied<3;denied++){
            boolean[] flags={true,true,true};flags[denied]=false;
            var result=service(flags[0],flags[1],flags[2]).inspect(KEY,AS_OF);
            assertEquals("STR177_RESEARCH_SOURCE_OR_DISCOVERY_RIGHTS_MISSING",
                result.evaluatorReasonCode());
            assertEquals("NOT_RUN",result.evaluatorStatus());
            assertFalse(result.independentlyVerifiedHistoricalE2E());
        }
        verifyNoInteractions(source,calendar);
    }

    @Test
    void completedOriginalTenSessionsInvokeActualMeanReversionEvaluator() {
        withData(ten());
        var result=service(true,true,true).inspect(KEY,AS_OF);
        assertEquals("ORIGINAL_SOURCE_EVALUATOR_RESEARCH_ONLY",result.state());
        assertEquals("FUT_MR_LOW_EXTREME_REBOUND_OBSERVED",
            result.evaluatorReasonCode());
        assertEquals("RESEARCH_CANDIDATE",result.evaluatorStatus());
        assertEquals(10,result.originalRowsObserved());
        assertEquals(10,result.verifiedCompletedSessions());
        assertEquals(64,result.originalSourceInputDigest().length());
        assertFalse(result.independentlyVerifiedHistoricalE2E());
        assertFalse(result.paperLedgerE2E());
        assertFalse(result.liveBrokerE2E());
        assertFalse(result.brokerOrdersPlaced());
        verify(source).readExactReplayContract(eq(KEY),any(),any(),eq(AS_OF));
    }

    @Test
    void expiryOrOriginalFutureKnownTimeBlocksBeforeEvaluator(){
        var rows=new ArrayList<>(ten());
        var last=rows.getLast();var b=last.bar();
        var future=new FnoVerifiedCompletedOhlcvOiStore.VerifiedBar(
            b.provider(),b.instrumentKey(),b.underlyingKey(),
            b.listedFrom(),b.expiry(),b.lotSize(),b.sessionDate(),
            b.open(),b.high(),b.low(),b.close(),b.tradedVolumeUnits(),
            b.openInterestUnits(),b.ohlcvFirstAvailableAt(),
            AS_OF.plusSeconds(60),b.verifiedSessionCloseAt());
        rows.set(9,new FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar(
            future,HASH,HASH,"b".repeat(64)));
        withData(rows);
        assertEquals("STR177_CONTRACT_CALENDAR_OR_CUSTODY_INVALID",
            service(true,true,true).inspect(KEY,AS_OF).evaluatorReasonCode());
    }

    @Test
    void mixedProviderDuplicateOrTamperedListingHashAlwaysBlocked(){
        var rows=new ArrayList<>(ten());
        rows.set(6,rows.get(5));
        withData(rows);
        assertEquals("STR177_CONTRACT_CALENDAR_OR_CUSTODY_INVALID",
            service(true,true,true).inspect(KEY,AS_OF).evaluatorReasonCode());
        var original=new ArrayList<>(ten());
        var first=original.getFirst();
        original.set(0,new FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar(
            first.bar(),HASH,HASH,"not-a-real-digest"));
        withData(original);
        assertEquals("STR177_CONTRACT_CALENDAR_OR_CUSTODY_INVALID",
            service(true,true,true).inspect(KEY,AS_OF).evaluatorReasonCode());
    }

    @Test
    void missingTenSessionsOrBadQueryScopeFailsClosed() {
        withData(ten().subList(0,9));
        var result=service(true,true,true).inspect(KEY,AS_OF);
        assertEquals("STR177_TEN_RIGHTS_ATTESTED_SESSIONS_MISSING",
            result.evaluatorReasonCode());
        assertEquals("STR177_REPLAY_SCOPE_INVALID",
            service(true,true,true).inspect("NSE_EQ|BAD",AS_OF).evaluatorReasonCode());
        assertEquals("STR177_REPLAY_SCOPE_INVALID",
            service(true,true,true).inspect(KEY,CLOCK.instant().plusSeconds(1))
                .evaluatorReasonCode());
    }

    @Test
    void SQLExceptionIsSanitizedAndNeverLeaksCredentials(){
        when(source.readExactReplayContract(eq(KEY),any(),any(),eq(AS_OF)))
            .thenThrow(new IllegalStateException("secret-source-password"));
        var result=service(true,true,true).inspect(KEY,AS_OF);
        assertEquals("STR177_ORIGINAL_RIGHTS_JOIN_SOURCE_UNAVAILABLE",
            result.evaluatorReasonCode());
        assertFalse(result.toString().contains("password"));
    }
}
