package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OwnerAngelOneHistoricalProviderTest {
    @Test
    void privateSourceUsesApplicationSessionAndStrictCandleParser() throws Exception {
        var feed = mock(AppAngelOneMarketFeedService.class);
        var data = new ObjectMapper().readTree(
                "[[\"2026-11-09T09:15:00+05:30\",100,105,99,103,200]]");
        when(feed.historicalCandles("NSE", "2885", "FIVE_MINUTE",
                "2026-11-09 09:15", "2026-11-09 15:30")).thenReturn(data);

        var source = new OwnerAngelOneHistoricalProvider(feed, new HistoricalCandleParser());
        var candles = source.historicalCandles("NSE", "2885", "FIVE_MINUTE",
                "2026-11-09 09:15", "2026-11-09 15:30");

        assertThat(source.source()).isEqualTo("ANGEL_ONE_SMARTAPI");
        assertThat(candles).hasSize(1);
        assertThat(candles.getFirst().close()).isEqualByComparingTo(new BigDecimal("103"));
        verify(feed).historicalCandles("NSE", "2885", "FIVE_MINUTE",
                "2026-11-09 09:15", "2026-11-09 15:30");
    }

    @Test
    void rejectsMalformedApplicationCandleWithoutSynthesizingData() throws Exception {
        var feed = mock(AppAngelOneMarketFeedService.class);
        var invalid = new ObjectMapper().readTree(
                "[[\"2026-11-09T09:15:00+05:30\",100,90,99,103,200]]");
        when(feed.historicalCandles(anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(invalid);
        var source = new OwnerAngelOneHistoricalProvider(feed, new HistoricalCandleParser());

        assertThatThrownBy(() -> source.historicalCandles("NSE", "2885",
                "FIVE_MINUTE", "2026-11-09 09:15", "2026-11-09 15:30"))
                .isInstanceOf(MarketDataException.class);
    }
}
