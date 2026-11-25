package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyIvRankSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyIvRankSourceStore.*;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Fixtures are deterministic only; NOT real exchange/rights E2E validation. */
class FnoIvRankResearchServiceTest {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final String SOURCE="UPSTOX_ANALYTICS";
    static MarketSessionCalendar calendar(){
        var c=mock(MarketSessionCalendar.class);
        when(c.isInsideCashSession(NOW)).thenReturn(true);
        when(c.isTradingDay(any(LocalDate.class))).thenReturn(true);
        return c;
    }
    static Series evidence(){
        var daily=FnoIvRankMeanReversionV1Test.candles();
        var marks=FnoIvRankMeanReversionV1Test.ivHistory();
        var rows=new ArrayList<Historical>();
        for(int i=0;i<daily.size();i++){
            var bar=daily.get(i);var iv=marks.get(i);
            rows.add(new Historical(bar.session(),bar.provider(),bar.open(),bar.high(),
                bar.low(),bar.close(),bar.volume(),bar.exchangeCloseAt(),
                bar.firstKnownAt(),iv.atmIvPercent(),iv.firstKnownAt()));
        }
        return new Series(List.copyOf(rows),new Current(SOURCE,
            FnoLongOptionCrossoverV1.UNDERLYING,new BigDecimal("38"),
            NOW.minusSeconds(40),NOW.minusSeconds(30)));
    }
    static FnoVerifiedNiftyIvRankSourceStore source(){
        var store=mock(FnoVerifiedNiftyIvRankSourceStore.class);
        when(store.oneCurrent(NOW)).thenReturn(Optional.of(evidence()));
        return store;
    }
    static FnoIvRankResearchService service(FnoVerifiedNiftyIvRankSourceStore store,
                                              boolean allowed) {
        return new FnoIvRankResearchService(store,calendar(),
            allowed,allowed,allowed,allowed);
    }
    @Test void originalSourceJoined252SessionsCallsExistingRankEvaluator(){
        var store=source();var result=service(store,true).evaluate(NOW);
        assertEquals("strategy.STR-201",result.strategyId());
        assertEquals("RESEARCH_CANDIDATE",result.status());
        assertEquals("IV_RANK_HIGH_IV_PULLBACK_RESEARCH_ONLY",result.reasonCode());
        assertEquals(252,result.historySessions());
        assertEquals(1,result.freshQuotes());
        assertEquals("ORIGINAL_30_DAY_ATM_IV_200_SOURCE_ALIGNED_CANDLES_NO_ORDER",
            result.inputState());
        verify(store).oneCurrent(NOW);
        var negative=FnoIvRankMeanReversionV1.evaluate(List.of(),List.of(),null,
            new FnoIvRankMeanReversionV1.Rights(false,false,false,false),null,NOW);
        assertFalse(negative.executable());assertFalse(negative.orderPlaced());
        assertFalse(negative.liveTradeAuthorized());
    }
    @Test void deniedSourceEntitlementsNeverReadPrivateHistoricalRecords(){
        var store=mock(FnoVerifiedNiftyIvRankSourceStore.class);
        var result=service(store,false).evaluate(NOW);
        assertEquals("BLOCKED_RIGHTS",result.status());
        assertEquals("IV_RANK_ORIGINAL_HISTORY_OR_IV_RIGHTS_UNVERIFIED",result.reasonCode());
        verifyNoInteractions(store);
    }
    @Test void missing252LicensedSessionsNeverSubstitutesSyntheticIv(){
        var store=source();when(store.oneCurrent(NOW)).thenReturn(Optional.empty());
        assertEquals("IV_RANK_200_ALIGNED_IV_AND_OHLC_SESSIONS_MISSING",
            service(store,true).evaluate(NOW).reasonCode());
        var p=evidence();
        when(store.oneCurrent(NOW)).thenReturn(Optional.of(
            new Series(p.historical().subList(0,199),p.current())));
        assertEquals("IV_RANK_HISTORY_OR_NORMALIZATION_INVALID",
            service(store,true).evaluate(NOW).reasonCode());
    }
    @Test void historicalWrongProviderFailsTypedEvaluator(){
        var store=source();var p=evidence();var rows=new ArrayList<>(p.historical());
        var old=rows.get(0);
        rows.set(0,new Historical(old.session(),"ANGEL_ONE_SMARTAPI",old.open(),
            old.high(),old.low(),old.close(),old.volume(),old.exchangeCloseAt(),
            old.candleFirstKnownAt(),old.annualized30DayIvPercent(),old.ivFirstKnownAt()));
        when(store.oneCurrent(NOW)).thenReturn(Optional.of(new Series(rows,p.current())));
        assertEquals("IV_RANK_ORIGINAL_HISTORY_INVALID",
            service(store,true).evaluate(NOW).reasonCode());
    }
    @Test void zeroIvAndInvalidFutureKnownHistoryCannotBecomeSignals(){
        var store=source();var p=evidence();var rows=new ArrayList<>(p.historical());
        var old=rows.get(0);
        rows.set(0,new Historical(old.session(),old.provider(),old.open(),
            old.high(),old.low(),old.close(),old.volume(),old.exchangeCloseAt(),
            old.candleFirstKnownAt(),BigDecimal.ZERO,old.ivFirstKnownAt()));
        when(store.oneCurrent(NOW)).thenReturn(Optional.of(new Series(rows,p.current())));
        assertEquals("IV_RANK_ORIGINAL_HISTORY_INVALID",service(store,true).evaluate(NOW).reasonCode());
        rows.set(0,new Historical(old.session(),old.provider(),old.open(),
            old.high(),old.low(),old.close(),old.volume(),old.exchangeCloseAt(),
            old.candleFirstKnownAt(),old.annualized30DayIvPercent(),NOW.plusSeconds(1)));
        assertEquals("IV_RANK_HISTORY_SESSION_ORIGINAL_TIME_INVALID",
            service(store,true).evaluate(NOW).reasonCode());
    }
    @Test void currentIvMustStayOriginalAndPointInTime(){
        var store=source();var p=evidence();
        when(store.oneCurrent(NOW)).thenReturn(Optional.of(new Series(p.historical(),
            new Current(SOURCE,FnoLongOptionCrossoverV1.UNDERLYING,
                BigDecimal.ZERO,NOW.minusSeconds(40),NOW.minusSeconds(30)))));
        assertEquals("IV_RANK_CURRENT_SOURCE_IV_INVALID",
            service(store,true).evaluate(NOW).reasonCode());
    }
    @Test void providerErrorsAreRedactedAndNeverPermitOrders(){
        var store=source();when(store.oneCurrent(NOW)).thenThrow(
            new IllegalStateException("SSM secret live token"));
        var row=service(store,true).evaluate(NOW);
        assertEquals("IV_RANK_ORIGINAL_HISTORY_SOURCE_READ_FAILED",row.reasonCode());
        assertFalse(row.toString().contains("secret"));
    }
}
