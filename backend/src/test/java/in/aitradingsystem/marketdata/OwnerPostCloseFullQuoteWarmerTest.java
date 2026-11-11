package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.provider.angelone.AngelOneQuoteDetailParser;
import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import in.aitradingsystem.scanner.ResearchScanRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnerPostCloseFullQuoteWarmerTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 11);
    private static final Instant POST_CAS = DAY.atTime(15, 45).atZone(IST).toInstant();
    private static final Instant AUCTION_EVENT = DAY.atTime(15, 34).atZone(IST).toInstant();
    private static final Instrument RELIANCE = new Instrument(42L, "NSE_CM", "NSE",
        "2885", "RELIANCE-EQ", "Reliance", "EQ", null, null, 1,
        new BigDecimal("0.05"), UUID.randomUUID());

    private static MarketSession session(LocalDate date, LocalTime close, boolean trading) {
        return new MarketSession(date, date.atTime(9, 15).atZone(IST),
            date.atTime(close).atZone(IST), trading);
    }

    @Test
    void nseCasFullQuoteIsValidButDoesNotExtendCashOrOrderHours() {
        var calendar = mock(MarketSessionCalendar.class);
        when(calendar.session(DAY)).thenReturn(session(DAY, LocalTime.of(15, 30), true));
        assertThat(VerifiedFullQuoteSession.accepts("NSE", AUCTION_EVENT, POST_CAS, calendar)).isTrue();
        assertThat(VerifiedFullQuoteSession.accepts("BSE", AUCTION_EVENT, POST_CAS, calendar)).isFalse();
        assertThat(VerifiedFullQuoteSession.accepts("NSE", DAY.atTime(16, 0).atZone(IST).toInstant(),
            DAY.atTime(16, 1).atZone(IST).toInstant(), calendar)).isFalse();
        assertThat(VerifiedFullQuoteSession.accepts("NSE", POST_CAS.plusSeconds(1), POST_CAS, calendar))
            .isFalse();
        when(calendar.session(DAY)).thenReturn(session(DAY, LocalTime.of(15, 15), true));
        assertThat(VerifiedFullQuoteSession.accepts("NSE", AUCTION_EVENT, POST_CAS, calendar)).isFalse();
        when(calendar.session(DAY)).thenReturn(session(DAY, LocalTime.of(15, 30), false));
        assertThat(VerifiedFullQuoteSession.accepts("NSE", AUCTION_EVENT, POST_CAS, calendar)).isFalse();
        verify(calendar, never()).isInsideCashSession(POST_CAS);
    }

    @Test
    void postCloseUsesOnlyPrivateNseFullQuoteAndPreservesBrokerData() {
        var feed = mock(AppAngelOneMarketFeedService.class);
        var cal = mock(MarketSessionCalendar.class);
        var scans = mock(ResearchScanRepository.class);
        var store = mock(MarketDetailSnapshotStore.class);
        when(cal.session(DAY)).thenReturn(session(DAY, LocalTime.of(15, 30), true));
        when(feed.available()).thenReturn(true);
        when(scans.marketFeedPriorityUniverse(3000)).thenReturn(List.of(RELIANCE));
        var detail = new AngelOneQuoteDetailParser.Detail(
            new BigDecimal("1335.40"), new BigDecimal("1333.50"),
            new BigDecimal("1349.40"), new BigDecimal("1327.00"),
            new BigDecimal("1324.40"), 10000L, AUCTION_EVENT,
            null, null, null, 0L, 0L, List.of(), List.of());
        when(feed.details(anyList())).thenReturn(Map.of("2885", detail));

        var reconciler = new OwnerPostCloseFullQuoteWarmer(feed, cal, scans, store,
            Clock.fixed(POST_CAS, ZoneOffset.UTC), 3000);
        reconciler.reconcile();
        reconciler.reconcile();

        verify(feed, times(1)).details(List.of(
            new AngelOneQuoteDetailParser.Expected("2885", "RELIANCE-EQ")));
        verify(store, times(1)).save(argThat(q ->
            q.instrumentId() == 42L && "NSE".equals(q.exchange())
            && "RELIANCE-EQ".equals(q.tradingSymbol())
            && q.ltp().compareTo(new BigDecimal("1335.40")) == 0
            && q.high().compareTo(new BigDecimal("1349.40")) == 0
            && AUCTION_EVENT.equals(q.exchangeAt())
            && "ANGEL_ONE_SMARTAPI_FULL_APP".equals(q.source())));
    }

    @Test
    void outsideWindowOrSpecialSessionNeverCallsBroker() {
        var feed = mock(AppAngelOneMarketFeedService.class);
        var cal = mock(MarketSessionCalendar.class);
        var scans = mock(ResearchScanRepository.class);
        var store = mock(MarketDetailSnapshotStore.class);
        new OwnerPostCloseFullQuoteWarmer(feed, cal, scans, store,
            Clock.fixed(DAY.atTime(15, 36).atZone(IST).toInstant(), ZoneOffset.UTC), 3000)
            .reconcile();
        verifyNoInteractions(feed, scans, store);

        when(cal.session(DAY)).thenReturn(session(DAY, LocalTime.of(15, 15), true));
        new OwnerPostCloseFullQuoteWarmer(feed, cal, scans, store,
            Clock.fixed(POST_CAS, ZoneOffset.UTC), 3000).reconcile();
        verifyNoInteractions(feed, scans, store);
    }

    @Test
    void invalidUnverifiedAfterCloseTimestampNeverPersists() {
        var feed = mock(AppAngelOneMarketFeedService.class);
        var cal = mock(MarketSessionCalendar.class);
        var scans = mock(ResearchScanRepository.class);
        var store = mock(MarketDetailSnapshotStore.class);
        when(cal.session(DAY)).thenReturn(session(DAY, LocalTime.of(15, 30), true));
        when(feed.available()).thenReturn(true);
        when(scans.marketFeedPriorityUniverse(3000)).thenReturn(List.of(RELIANCE));
        var invalid = new AngelOneQuoteDetailParser.Detail(
            new BigDecimal("1335.40"), new BigDecimal("1333.50"),
            new BigDecimal("1349.40"), new BigDecimal("1327.00"),
            new BigDecimal("1324.40"), 10000L,
            DAY.atTime(16, 5).atZone(IST).toInstant(),
            null, null, null, 0L, 0L, List.of(), List.of());
        when(feed.details(anyList())).thenReturn(Map.of("2885", invalid));
        var reconciler = new OwnerPostCloseFullQuoteWarmer(feed, cal, scans, store,
            Clock.fixed(POST_CAS, ZoneOffset.UTC), 3000);
        reconciler.reconcile();
        verifyNoInteractions(store);
    }
}
