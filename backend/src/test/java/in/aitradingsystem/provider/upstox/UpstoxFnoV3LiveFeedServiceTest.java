package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UpstoxFnoV3LiveFeedServiceTest {
    private final UpstoxAnalyticsSource source = mock(UpstoxAnalyticsSource.class);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final WebSocket socket = mock(WebSocket.class);
    private final AtomicReference<WebSocket.Listener> listener = new AtomicReference<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-14T10:00:00Z"), ZoneOffset.UTC);

    private UpstoxFnoV3LiveFeedService service() {
        when(source.enabled()).thenReturn(true);
        when(source.authorizeFnoMarketFeedV3()).thenReturn(URI.create(
                "wss://prod-feed.upstox.com/market-data-feeder/v3/feeds?code=test-one-use"));
        when(socket.sendBinary(any(ByteBuffer.class), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.sendClose(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(socket));
        return new UpstoxFnoV3LiveFeedService(source, clock, (uri, capture) -> {
            listener.set(capture);
            return CompletableFuture.completedFuture(socket);
        }, executor);
    }

    @Test
    void connectsOnlyOnExplicitStartSubscribesBinaryAndDecodesRealV3Schema() {
        var s = service();
        try {
            assertThat(s.status().state()).isEqualTo("STOPPED");
            verify(source, never()).authorizeFnoMarketFeedV3();
            var start = s.start(List.of("NSE_FO|61001"));
            assertThat(start.subscriptions()).isEqualTo(1);
            listener.get().onOpen(socket);
            var binary = org.mockito.ArgumentCaptor.forClass(ByteBuffer.class);
            verify(socket).sendBinary(binary.capture(), eq(true));
            assertThat(new String(binary.getValue().array(),
                    java.nio.charset.StandardCharsets.UTF_8))
                    .contains("\"method\":\"sub\"", "\"mode\":\"full\"", "NSE_FO|61001");
            listener.get().onBinary(socket,
                    ByteBuffer.wrap(UpstoxFnoV3FeedCodecTest.validFirstLevel()), true);
            var status = s.status();
            assertThat(status.state()).isEqualTo("CONNECTED");
            assertThat(status.observedInstruments()).isEqualTo(1);
            assertThat(status.decodedFrames()).isEqualTo(1);
            assertThat(status.latestExchangeTradeAt())
                    .isEqualTo(Instant.parse("2026-11-13T09:15:00Z"));
            assertThat(status.providerTradeTimeAvailable()).isTrue();
            assertThat(status.displayRightsVerified()).isFalse();
            assertThat(status.trainingRightsVerified()).isFalse();
            assertThat(status.liveTradingEnabled()).isFalse();
            verify(source, times(1)).authorizeFnoMarketFeedV3();
        } finally {
            s.shutdown();
        }
    }

    @Test
    void cannotStartTwoConnectionsOrSubscribeArbitraryKeys() {
        var s = service();
        try {
            assertThatThrownBy(() -> s.start(List.of("NSE_EQ|INE002A01018")))
                    .hasMessage("UPSTOX_FNO_STREAM_INVALID_START");
            assertThatThrownBy(() -> s.start(List.of("NSE_FO|61001", "NSE_FO|61001")))
                    .hasMessage("UPSTOX_FNO_STREAM_INVALID_START");
            assertThat(s.status().state()).isEqualTo("STOPPED");
            s.start(List.of("NSE_FO|61001"));
            assertThatThrownBy(() -> s.start(List.of("NSE_FO|61002")))
                    .hasMessage("UPSTOX_FNO_STREAM_ALREADY_STARTED");
            verify(source, times(1)).authorizeFnoMarketFeedV3();
        } finally {
            s.shutdown();
        }
    }

    @Test
    void rejectsNonProtobufTextAndStaleCallbacksAfterStop() {
        var s = service();
        try {
            s.start(List.of("NSE_FO|61001"));
            var callbacks = listener.get();
            callbacks.onOpen(socket);
            callbacks.onText(socket, "{\"ltp\":100}", true);
            assertThat(s.status().discardedFrames()).isEqualTo(1);
            s.stop();
            assertThat(s.status().state()).isEqualTo("STOPPED");
            callbacks.onBinary(socket,
                    ByteBuffer.wrap(UpstoxFnoV3FeedCodecTest.validFirstLevel()), true);
            assertThat(s.status().observedInstruments()).isZero();
            callbacks.onClose(socket, 1006, "remote error includes secret");
            assertThat(s.status().state()).isEqualTo("STOPPED");
        } finally {
            s.shutdown();
        }
    }

    @Test
    void invalidBinaryFrameCannotBeReportedAsValidTrade() {
        var s = service();
        try {
            s.start(List.of("NSE_FO|61001"));
            listener.get().onOpen(socket);
            listener.get().onBinary(socket, ByteBuffer.wrap(new byte[] {1, 2, 3}), true);
            assertThat(s.status().decodedFrames()).isZero();
            assertThat(s.status().discardedFrames()).isEqualTo(1);
            assertThat(s.status().latestExchangeTradeAt()).isNull();
        } finally {
            s.shutdown();
        }
    }
}
