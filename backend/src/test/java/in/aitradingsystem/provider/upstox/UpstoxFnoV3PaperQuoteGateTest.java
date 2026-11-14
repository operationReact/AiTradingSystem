package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UpstoxFnoV3PaperQuoteGateTest {
    private static final Instant TRADE = Instant.parse("2026-11-13T09:15:00Z");
    private final UpstoxAnalyticsSource source=mock(UpstoxAnalyticsSource.class);
    private final WebSocket websocket=mock(WebSocket.class);
    private final AtomicReference<WebSocket.Listener> listener=new AtomicReference<>();

    private UpstoxFnoV3LiveFeedService create(Clock clock) {
        when(source.enabled()).thenReturn(true);
        when(source.authorizeFnoMarketFeedV3()).thenReturn(URI.create(
                "wss://prod-feed.upstox.com/market-data-feeder/v3/feeds?code=test"));
        when(websocket.sendBinary(any(ByteBuffer.class),eq(true)))
                .thenReturn(CompletableFuture.completedFuture(websocket));
        return new UpstoxFnoV3LiveFeedService(source,clock,
                (uri,handler)->{
                    listener.set(handler);
                    return CompletableFuture.completedFuture(websocket);
                },Executors.newSingleThreadScheduledExecutor());
    }

    private void feed(UpstoxFnoV3LiveFeedService service) {
        service.start(List.of("NSE_FO|61001"));
        listener.get().onOpen(websocket);
        listener.get().onBinary(websocket,
                ByteBuffer.wrap(UpstoxFnoV3FeedCodecTest.validFirstLevel()),true);
    }

    @Test
    void returnsOnlyRecentObservedExchangeTimestampedBidAsk() {
        var s=create(Clock.fixed(TRADE.plusSeconds(2),ZoneOffset.UTC));
        try{
            assertThat(s.eligiblePaperQuote("NSE_FO|61001")).isEmpty();
            feed(s);
            var quote=s.eligiblePaperQuote("NSE_FO|61001").orElseThrow();
            assertThat(quote.instrumentKey()).isEqualTo("NSE_FO|61001");
            assertThat(quote.exchangeAt()).isEqualTo(TRADE);
            assertThat(quote.bid()).isEqualByComparingTo("119.5");
            assertThat(quote.ask()).isEqualByComparingTo("121");
            assertThat(s.eligiblePaperQuote("NSE_FO|999999")).isEmpty();
            s.stop();
            assertThat(s.eligiblePaperQuote("NSE_FO|61001")).isEmpty();
        }finally{s.shutdown();}
    }

    @Test
    void rejectsYesterdayQuoteEvenIfJustReceivedOverConnectedSocket() {
        var s=create(Clock.fixed(TRADE.plusSeconds(86400),ZoneOffset.UTC));
        try{
            feed(s);
            assertThat(s.status().decodedFrames()).isEqualTo(1);
            assertThat(s.eligiblePaperQuote("NSE_FO|61001")).isEmpty();
        }finally{s.shutdown();}
    }

    @Test
    void rejectsUnknownAndNonDerivativeKeysWithoutAnyProviderReads() {
        var s=create(Clock.fixed(TRADE.plusSeconds(2),ZoneOffset.UTC));
        try{
            assertThat(s.eligiblePaperQuote("NSE_EQ|INE002A01018")).isEmpty();
            assertThat(s.eligiblePaperQuote(null)).isEmpty();
            verify(source,never()).authorizeFnoMarketFeedV3();
        }finally{s.shutdown();}
    }
    @Test
    void disconnectClearsLastQuoteAndLateOldSocketFramesCannotRearmPaper() {
        var s=create(Clock.fixed(TRADE.plusSeconds(2),ZoneOffset.UTC));
        try {
            feed(s);
            assertThat(s.eligiblePaperQuote("NSE_FO|61001")).isPresent();
            var priorSocketListener=listener.get();
            priorSocketListener.onError(websocket,new java.io.IOException("closed"));
            assertThat(s.status().state()).isEqualTo("RECONNECTING");
            assertThat(s.status().observedInstruments()).isZero();
            assertThat(s.status().latestExchangeTradeAt()).isNull();
            assertThat(s.eligiblePaperQuote("NSE_FO|61001")).isEmpty();

            // The old socket can still dispatch one more callback. It cannot
            // repopulate observed quotes or mark the new stream connected.
            priorSocketListener.onBinary(websocket,
                    ByteBuffer.wrap(UpstoxFnoV3FeedCodecTest.validFirstLevel()),true);
            assertThat(s.status().state()).isEqualTo("RECONNECTING");
            assertThat(s.status().observedInstruments()).isZero();
            assertThat(s.eligiblePaperQuote("NSE_FO|61001")).isEmpty();
            s.stop();
            assertThat(s.status().state()).isEqualTo("STOPPED");
        } finally {
            s.shutdown();
        }
    }

}
