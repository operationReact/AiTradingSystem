package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.MarketLastTickStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.scanner.ResearchScanRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnerLiveFeedWarmerTest {
    private static final Instant NOW = Instant.parse("2026-11-09T05:00:00Z");
    private static final Instrument NSE = new Instrument(42L, "NSE_CM", "NSE",
        "2885", "RELIANCE-EQ", "Reliance", "EQ", null, null, 1,
        new BigDecimal("0.05"), UUID.randomUUID());

    @Test
    void closedMarketDoesNothingAndNeverAuthenticates() {
        var feed = mock(AppAngelOneMarketFeedService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var scans = mock(ResearchScanRepository.class);
        var lastTicks = mock(MarketLastTickStore.class);
        when(calendar.isInsideCashSession(NOW)).thenReturn(false);

        new OwnerLiveFeedWarmer(feed, calendar, scans, lastTicks,
            Clock.fixed(NOW, ZoneOffset.UTC), 50).warm();

        verifyNoInteractions(feed, scans, lastTicks);
    }

    @Test
    void optInWarmUsesAppSessionAndPriorityUniverseNotAnotherOwnerSession() {
        var feed = mock(AppAngelOneMarketFeedService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var scans = mock(ResearchScanRepository.class);
        var lastTicks = mock(MarketLastTickStore.class);
        when(calendar.isInsideCashSession(NOW)).thenReturn(true);
        when(feed.available()).thenReturn(true);
        when(scans.marketFeedPriorityUniverse(50)).thenReturn(List.of(NSE));
        var reply = List.of(new CustomerAngelOneLiveQuoteService.LiveQuote(42L, "NSE",
            "RELIANCE-EQ", new BigDecimal("101.50"), NOW, NOW,
            "LIVE", "ANGEL_ONE_SMARTAPI_WS_V2"));
        when(feed.snapshot(List.of(NSE))).thenReturn(reply);

        new OwnerLiveFeedWarmer(feed, calendar, scans, lastTicks,
            Clock.fixed(NOW, ZoneOffset.UTC), 1000).warm();

        verify(scans).marketFeedPriorityUniverse(50);
        verify(feed).snapshot(List.of(NSE));
        verify(lastTicks).recordLive(reply);
    }

    @Test
    void disabledSsmFeedDoesNotQueryUniverseOrPersist() {
        var feed = mock(AppAngelOneMarketFeedService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var scans = mock(ResearchScanRepository.class);
        var lastTicks = mock(MarketLastTickStore.class);
        when(calendar.isInsideCashSession(NOW)).thenReturn(true);

        new OwnerLiveFeedWarmer(feed, calendar, scans, lastTicks,
            Clock.fixed(NOW, ZoneOffset.UTC), 50).warm();

        verify(feed).available();
        verifyNoInteractions(scans, lastTicks);
        verify(feed, never()).snapshot(anyList());
    }

    @Test
    void warmUpFailureIsContainedSoSchedulerKeepsRunning() {
        var feed = mock(AppAngelOneMarketFeedService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var scans = mock(ResearchScanRepository.class);
        var lastTicks = mock(MarketLastTickStore.class);
        when(calendar.isInsideCashSession(NOW)).thenReturn(true);
        when(feed.available()).thenReturn(true);
        when(scans.marketFeedPriorityUniverse(50)).thenThrow(new IllegalStateException("db down"));

        var warmer = new OwnerLiveFeedWarmer(feed, calendar, scans, lastTicks,
            Clock.fixed(NOW, ZoneOffset.UTC), 50);
        assertThatCode(warmer::warm).doesNotThrowAnyException();
        verify(feed, never()).snapshot(anyList());
        verifyNoInteractions(lastTicks);
    }

    @Test
    void configuredWebsocketBudgetNeverExceedsProviderWatchCap() {
        assertThat(CustomerAngelOneLiveQuoteService.boundedWatchCap(1000)).isEqualTo(50);
        assertThat(CustomerAngelOneLiveQuoteService.boundedWatchCap(20)).isEqualTo(20);
        assertThatThrownBy(() -> CustomerAngelOneLiveQuoteService.boundedWatchCap(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveUniverseLimit() {
        assertThatThrownBy(() -> new OwnerLiveFeedWarmer(mock(AppAngelOneMarketFeedService.class),
            mock(MarketSessionCalendar.class), mock(ResearchScanRepository.class),
            mock(MarketLastTickStore.class), Clock.fixed(NOW, ZoneOffset.UTC), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
