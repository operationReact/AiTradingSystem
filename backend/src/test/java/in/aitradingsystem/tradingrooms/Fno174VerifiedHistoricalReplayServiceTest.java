package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoVerifiedFinalPriceOiSessionStore;
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

/** Fictional inputs test actual STR-174, never original provider replay E2E. */
class Fno174VerifiedHistoricalReplayServiceTest {
    private static final String KEY="NSE_FO|DEC_1223";
    private static final Instant AS_OF=Instant.parse("2026-11-23T11:00:00Z");
    private static final Clock CLOCK=Clock.fixed(
        Instant.parse("2026-11-23T12:00:00Z"),ZoneOffset.UTC);
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final int[] DAYS={16,17,18,19,20};
    private final FnoVerifiedFinalPriceOiSessionStore source=
        mock(FnoVerifiedFinalPriceOiSessionStore.class);
    private final MarketSessionCalendar calendar=mock(MarketSessionCalendar.class);

    private Fno174VerifiedHistoricalReplayService service(
            boolean history,boolean price,boolean discovery) {
        return new Fno174VerifiedHistoricalReplayService(
            source,calendar,CLOCK,history,price,discovery);
    }
    private static Instant close(LocalDate day) {
        return day.atTime(LocalTime.of(15,40)).atZone(IST).toInstant();
    }
    private static FnoVerifiedFinalPriceOiSessionStore.FinalSession row(
            int day,String closing,long oi) {
        var session=LocalDate.of(2026,11,day);
        return new FnoVerifiedFinalPriceOiSessionStore.FinalSession(
            "UPSTOX_ANALYTICS",KEY,"NSE_INDEX|Nifty 50",
            LocalDate.of(2026,12,24),75,session,
            new BigDecimal(closing),close(session).plusSeconds(120),
            oi,50000L,close(session).plusSeconds(900),close(session));
    }
    private static List<FnoVerifiedFinalPriceOiSessionStore.FinalSession> five() {
        return List.of(row(16,"100",100000),row(17,"104",95000),
            row(18,"108",90000),row(19,"112",85000),row(20,"116",80000));
    }
    private void data(List<FnoVerifiedFinalPriceOiSessionStore.FinalSession> rows) {
        when(source.readExactContract(eq(KEY),any(),any(),eq(AS_OF)))
            .thenReturn(rows);
        when(calendar.isTradingDay(any(LocalDate.class))).thenAnswer(inv->{
            LocalDate d=inv.getArgument(0);
            return d.getDayOfWeek()!=DayOfWeek.SATURDAY
                && d.getDayOfWeek()!=DayOfWeek.SUNDAY;
        });
    }

    @Test
    void researchRightsDeniedBeforeAnySqlAccess() {
        for(int denied=0;denied<3;denied++){
            boolean[] rights={true,true,true};rights[denied]=false;
            var result=service(rights[0],rights[1],rights[2])
                .inspect(KEY,AS_OF);
            assertEquals("STR174_FINAL_PRICE_OR_DISCOVERY_RIGHTS_NOT_VERIFIED",
                result.reason());
            assertFalse(result.historicalE2EAccepted());
        }
        verifyNoInteractions(source,calendar);
    }

    @Test
    void actualDivergenceEvaluatorRunsAgainstFinalizedSourceDataOnly() {
        data(five());
        var result=service(true,true,true).inspect(KEY,AS_OF);
        assertEquals("ORIGINAL_FINAL_SOURCE_RESEARCH_ONLY",result.state());
        assertEquals("RESEARCH_CANDIDATE",result.evaluatorStatus());
        assertEquals("PRICE_RISING_WITH_FALLING_OI",result.reason());
        assertEquals(5,result.originalSourceRows());
        assertEquals(5,result.completedSessions());
        assertFalse(result.historicalE2EAccepted());
        assertFalse(result.paperLedgerE2E());
        assertFalse(result.liveBrokerE2E());
        assertFalse(result.ordersPlaced());
        verify(source).readExactContract(eq(KEY),any(),any(),eq(AS_OF));
    }

    @Test
    void sourceNotYetPublishedByHistoricalDecisionFailsClosed() {
        var items=new ArrayList<>(five());
        var s=items.getLast();
        items.set(4,new FnoVerifiedFinalPriceOiSessionStore.FinalSession(
            s.providerLabel(),s.instrumentKey(),s.underlyingKey(),
            s.expiry(),s.lotSize(),s.sessionDate(),s.finalClosePrice(),
            AS_OF.plusSeconds(1),s.finalOpenInterestUnits(),
            s.tradedVolumeUnits(),s.finalizedOiFirstAvailableAt(),
            s.verifiedExchangeCloseAt()));
        data(items);
        assertEquals("STR174_ORIGINAL_FINAL_SOURCE_IDENTITY_OR_TIME_INVALID",
            service(true,true,true).inspect(KEY,AS_OF).reason());
    }

    @Test
    void impossibleVerifiedExchangeCloseCannotMasqueradeAsOfficial() {
        var items=new ArrayList<>(five());
        var s=items.getFirst();
        items.set(0,new FnoVerifiedFinalPriceOiSessionStore.FinalSession(
            s.providerLabel(),s.instrumentKey(),s.underlyingKey(),
            s.expiry(),s.lotSize(),s.sessionDate(),s.finalClosePrice(),
            s.finalPriceFirstAvailableAt(),s.finalOpenInterestUnits(),
            s.tradedVolumeUnits(),s.finalizedOiFirstAvailableAt(),
            s.verifiedExchangeCloseAt().minusSeconds(600)));
        data(items);
        assertEquals("STR174_ORIGINAL_FINAL_SOURCE_IDENTITY_OR_TIME_INVALID",
            service(true,true,true).inspect(KEY,AS_OF).reason());
    }

    @Test
    void fewerThanFiveSessionsAmbiguousSourceOrWrongScopeDoNotReplay() {
        data(five().subList(0,4));
        assertEquals("STR174_FIVE_ORIGINAL_FINAL_SESSIONS_MISSING",
            service(true,true,true).inspect(KEY,AS_OF).reason());
        assertEquals("STR174_HISTORICAL_DECISION_SCOPE_INVALID",
            service(true,true,true).inspect("NSE_EQ|STOCK",AS_OF).reason());
        assertEquals("STR174_HISTORICAL_DECISION_SCOPE_INVALID",
            service(true,true,true).inspect(KEY,CLOCK.instant().plusSeconds(1))
                .reason());
    }

    @Test
    void mixedProviderAndDuplicatedContractSessionRejected() {
        var items=new ArrayList<>(five());
        var v=items.getLast();
        items.set(4,new FnoVerifiedFinalPriceOiSessionStore.FinalSession(
            "ANGEL_ONE_SMARTAPI",v.instrumentKey(),v.underlyingKey(),
            v.expiry(),v.lotSize(),v.sessionDate(),v.finalClosePrice(),
            v.finalPriceFirstAvailableAt(),v.finalOpenInterestUnits(),
            v.tradedVolumeUnits(),v.finalizedOiFirstAvailableAt(),
            v.verifiedExchangeCloseAt()));
        data(items);
        assertEquals("STR174_ORIGINAL_FINAL_SOURCE_IDENTITY_OR_TIME_INVALID",
            service(true,true,true).inspect(KEY,AS_OF).reason());
        items.set(4,items.get(3));
        data(items);
        assertEquals("STR174_ORIGINAL_FINAL_SOURCE_IDENTITY_OR_TIME_INVALID",
            service(true,true,true).inspect(KEY,AS_OF).reason());
    }

    @Test
    void sourceExceptionIsSanitized() {
        when(source.readExactContract(eq(KEY),any(),any(),eq(AS_OF)))
            .thenThrow(new IllegalStateException("private broker password"));
        var result=service(true,true,true).inspect(KEY,AS_OF);
        assertEquals("STR174_ORIGINAL_FINAL_PRICE_OI_SOURCE_UNAVAILABLE",
            result.reason());
        assertFalse(result.toString().contains("password"));
    }
}
