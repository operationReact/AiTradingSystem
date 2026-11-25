package in.aitradingsystem.marketdata;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnerPrivateIntradayCollectorTest {
    private final InstrumentMasterService instruments=mock(InstrumentMasterService.class);
    private final AppAngelOneMarketFeedService feed=mock(AppAngelOneMarketFeedService.class);
    private final OwnerPrivateIntradayRepository repository=mock(OwnerPrivateIntradayRepository.class);
    private final Clock clock=Clock.fixed(Instant.parse("2026-11-20T11:00:00Z"),ZoneOffset.UTC);

    @Test void storedProviderCandlesStayInExactOwnerPrivateTableForBothIntervals() throws Exception {
        when(feed.available()).thenReturn(true);
        when(instruments.activeInstrumentBySymbol("NSE","RELIANCE-EQ")).thenReturn(
            Optional.of(new Instrument(42,"NSE_CM","NSE","2885","RELIANCE-EQ",
                "Reliance","EQ",null,null,1,new BigDecimal("0.05"),UUID.randomUUID())));
        when(repository.claim(eq("my-owner"),eq("2885"),anyString(),any(),any(Duration.class)))
            .thenReturn(true);
        var source=new ObjectMapper().createArrayNode();
        source.addArray().add("2026-11-19T09:15:00+05:30")
            .add(100).add(110).add(90).add(105).add(12345);
        when(feed.historicalCandles(eq("NSE"),eq("2885"),anyString(),
            anyString(),anyString())).thenReturn(source);
        var collector=new OwnerPrivateIntradayCollector(instruments,feed,
            new HistoricalCandleParser(),repository,"my-owner",clock);
        collector.collectOne();
        collector.collectOne();
        verify(repository).save(eq("my-owner"),eq("2885"),eq("RELIANCE-EQ"),
            eq("FIVE_MINUTE"),any(),any(),argThat(rows->rows.size()==1));
        verify(repository).save(eq("my-owner"),eq("2885"),eq("RELIANCE-EQ"),
            eq("ONE_HOUR"),any(),any(),argThat(rows->rows.size()==1));
        verify(feed,times(2)).available();
        verify(feed,times(2)).historicalCandles(eq("NSE"),eq("2885"),
            anyString(),anyString(),anyString());
        verifyNoMoreInteractions(feed);
    }

    @Test void currentSessionPersistsOnlyCompletedRealBarsInOwnerArchive() {
        var duringMarket=Clock.fixed(Instant.parse("2026-11-20T05:00:00Z"),ZoneOffset.UTC);
        when(feed.available()).thenReturn(true);
        when(instruments.activeInstrumentBySymbol("NSE","RELIANCE-EQ")).thenReturn(
            Optional.of(new Instrument(42,"NSE_CM","NSE","2885","RELIANCE-EQ",
                "Reliance","EQ",null,null,1,new BigDecimal("0.05"),UUID.randomUUID())));
        when(repository.claim(eq("my-owner"),eq("2885"),anyString(),any(),any(Duration.class)))
            .thenReturn(true);
        var source=new ObjectMapper().createArrayNode();
        source.addArray().add("2026-11-20T09:15:00+05:30")
            .add(100).add(110).add(90).add(105).add(12345);
        // 10:25 is a forming 5m candle at 10:30, and forming 1H too.
        source.addArray().add("2026-11-20T10:25:00+05:30")
            .add(106).add(112).add(105).add(109).add(2000);
        when(feed.historicalCandles(eq("NSE"),eq("2885"),anyString(),
            anyString(),anyString())).thenReturn(source);

        var collector=new OwnerPrivateIntradayCollector(instruments,feed,
            new HistoricalCandleParser(),repository,"my-owner",duringMarket);
        collector.collectOne();
        collector.collectOne();

        verify(repository).claim(eq("my-owner"),eq("2885"),eq("FIVE_MINUTE"),
            any(),eq(Duration.ofMinutes(5)));
        verify(repository).claim(eq("my-owner"),eq("2885"),eq("ONE_HOUR"),
            any(),eq(Duration.ofMinutes(30)));
        verify(repository).save(eq("my-owner"),eq("2885"),eq("RELIANCE-EQ"),
            eq("FIVE_MINUTE"),eq(LocalDate.of(2026,11,20)),any(),
            argThat(rows->rows.size()==1));
        verify(repository).save(eq("my-owner"),eq("2885"),eq("RELIANCE-EQ"),
            eq("ONE_HOUR"),eq(LocalDate.of(2026,11,20)),any(),
            argThat(rows->rows.size()==1));
        verify(feed,times(2)).historicalCandles(eq("NSE"),eq("2885"),
            anyString(),anyString(),contains("2026-11-20 10:30"));
    }

    @Test void closedSessionUsesLongCooldownAndNoIntradayPolling() {
        var weekend=Clock.fixed(Instant.parse("2026-11-21T05:00:00Z"),ZoneOffset.UTC);
        when(feed.available()).thenReturn(true);
        when(instruments.activeInstrumentBySymbol("NSE","RELIANCE-EQ")).thenReturn(
            Optional.of(new Instrument(42,"NSE_CM","NSE","2885","RELIANCE-EQ",
                "Reliance","EQ",null,null,1,new BigDecimal("0.05"),UUID.randomUUID())));
        when(repository.claim(eq("my-owner"),eq("2885"),anyString(),any(),any(Duration.class)))
            .thenReturn(true);
        when(feed.historicalCandles(eq("NSE"),eq("2885"),anyString(),
            anyString(),anyString())).thenReturn(new ObjectMapper().createArrayNode());
        new OwnerPrivateIntradayCollector(instruments,feed,
            new HistoricalCandleParser(),repository,"my-owner",weekend).collectOne();
        verify(repository).claim(eq("my-owner"),eq("2885"),eq("FIVE_MINUTE"),
            any(),eq(Duration.ofHours(12)));
        verify(feed).historicalCandles(eq("NSE"),eq("2885"),eq("FIVE_MINUTE"),
            anyString(),contains("2026-11-20 15:30"));
    }

    @Test void noOwnerIdentityNeverCallsProviderOrPersists() {
        when(feed.available()).thenReturn(true);
        new OwnerPrivateIntradayCollector(instruments,feed,new HistoricalCandleParser(),
            repository,"",clock).collectOne();
        verifyNoInteractions(instruments,repository);
        verify(feed,never()).historicalCandles(any(),any(),any(),any(),any());
    }
}
