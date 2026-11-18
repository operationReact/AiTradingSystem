package in.aitradingsystem.mind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.MarketLastTickStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import in.aitradingsystem.provider.angelone.CustomerAngelOneLiveQuoteService.LiveQuote;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Mind PAPER quote gate uses the stored tick when fresh, otherwise the authorized application-owned live feed.
 * A non-LIVE, failed or unavailable feed never produces a price.
 */
class MindQuoteSourceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T04:00:00Z"); // 09:30 IST, inside the session
    private static final Instrument RELIANCE = new Instrument(417378L, "NSE_EQ", "NSE", "2885", "RELIANCE-EQ",
            "RELIANCE INDUSTRIES", "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());

    private static MindPaperCycleService cycle(MarketLastTickStore store, AppAngelOneMarketFeedService feed) {
        MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);
        when(calendar.isInsideCashSession(any())).thenReturn(true);
        return new MindPaperCycleService(null, null, null, null, null, store, calendar, null,
                Clock.fixed(NOW, ZoneOffset.UTC), feed, "ANGEL_ONE_SMARTAPI");
    }

    private static MarketLastTickStore.LastTick fresh() {
        return new MarketLastTickStore.LastTick(new BigDecimal("2750.00"), new BigDecimal("2740.00"), NOW.minusSeconds(3));
    }

    @Test
    void freshStoredTickIsUsedWithoutCallingTheFeed() {
        MarketLastTickStore store = mock(MarketLastTickStore.class);
        when(store.lastFor(any())).thenReturn(Map.of(417378L, fresh()));
        AppAngelOneMarketFeedService feed = mock(AppAngelOneMarketFeedService.class);
        assertThat(cycle(store, feed).freshTick(RELIANCE, NOW)).isNotNull();
        verify(feed, never()).live(any());
    }

    @Test
    void missingStoredTickIsFilledFromTheApplicationLiveFeedThroughTheSameStore() {
        MarketLastTickStore store = mock(MarketLastTickStore.class);
        when(store.lastFor(any())).thenReturn(Map.of(), Map.of(417378L, fresh()));
        AppAngelOneMarketFeedService feed = mock(AppAngelOneMarketFeedService.class);
        when(feed.available()).thenReturn(true);
        LiveQuote live = new LiveQuote(417378L, "NSE", "RELIANCE-EQ", new BigDecimal("2750.00"),
                NOW.minusSeconds(3), NOW, "LIVE", "ANGEL_ONE_SMARTAPI_FULL_APP");
        when(feed.live(any())).thenReturn(live);
        assertThat(cycle(store, feed).freshTick(RELIANCE, NOW)).isNotNull();
        verify(store).recordLive(List.of(live));
    }

    @Test
    void nonLiveApplicationQuoteNeverProducesAPrice() {
        MarketLastTickStore store = mock(MarketLastTickStore.class);
        when(store.lastFor(any())).thenReturn(Map.of());
        AppAngelOneMarketFeedService feed = mock(AppAngelOneMarketFeedService.class);
        when(feed.available()).thenReturn(true);
        when(feed.live(any())).thenReturn(new LiveQuote(417378L, "NSE", "RELIANCE-EQ", null, null, NOW, "UNAVAILABLE", null));
        assertThat(cycle(store, feed).freshTick(RELIANCE, NOW)).isNull();
        verify(store, never()).recordLive(any());
    }

    @Test
    void feedFailureFailsClosedToNoQuote() {
        MarketLastTickStore store = mock(MarketLastTickStore.class);
        when(store.lastFor(any())).thenReturn(Map.of());
        AppAngelOneMarketFeedService feed = mock(AppAngelOneMarketFeedService.class);
        when(feed.available()).thenReturn(true);
        when(feed.live(any())).thenThrow(new IllegalStateException("provider timeout"));
        assertThat(cycle(store, feed).freshTick(RELIANCE, NOW)).isNull();
    }

    @Test
    void unavailableFeedIsNeverCalled() {
        MarketLastTickStore store = mock(MarketLastTickStore.class);
        when(store.lastFor(any())).thenReturn(Map.of());
        AppAngelOneMarketFeedService feed = mock(AppAngelOneMarketFeedService.class);
        when(feed.available()).thenReturn(false);
        assertThat(cycle(store, feed).freshTick(RELIANCE, NOW)).isNull();
        verify(feed, never()).live(any());
    }
}
