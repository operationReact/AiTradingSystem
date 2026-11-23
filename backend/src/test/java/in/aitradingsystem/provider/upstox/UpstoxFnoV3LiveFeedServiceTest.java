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

    private UpstoxFnoV3LiveFeedService service() { return service(clock); }

    private UpstoxFnoV3LiveFeedService service(Clock sourceClock) {
        when(source.enabled()).thenReturn(true);
        when(source.authorizeFnoMarketFeedV3()).thenReturn(URI.create(
                "wss://prod-feed.upstox.com/market-data-feeder/v3/feeds?code=test-one-use"));
        when(socket.sendBinary(any(ByteBuffer.class), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.sendClose(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(socket));
        return new UpstoxFnoV3LiveFeedService(source, sourceClock, (uri, capture) -> {
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
    void freshExchangeLttCanSafelyDisplayV3LtpOiGreeksAndPriorClose() {
        var closeTime = Clock.fixed(
            Instant.parse("2026-11-13T09:15:02Z"), ZoneOffset.UTC);
        var s = service(closeTime);
        try {
            s.start(List.of("NSE_FO|61001"));
            listener.get().onOpen(socket);
            listener.get().onBinary(socket,
                ByteBuffer.wrap(UpstoxFnoV3FeedCodecTest.validFirstLevel()), true);
            var latest = s.eligibleDisplayQuote("NSE_FO|61001");
            assertThat(latest).isPresent();
            assertThat(latest.orElseThrow().ltp()).isEqualByComparingTo("120.5");
            assertThat(latest.orElseThrow().previousClose())
                .isEqualByComparingTo("130");
            assertThat(latest.orElseThrow().openInterest()).isEqualTo(5000L);
            assertThat(latest.orElseThrow().volume()).isEqualTo(125000L);
            assertThat(latest.orElseThrow().lastTradedQuantity()).isEqualTo(65L);
            assertThat(latest.orElseThrow().bidQuantity()).isEqualTo(100L);
            assertThat(latest.orElseThrow().askQuantity()).isEqualTo(200L);
            assertThat(latest.orElseThrow().delta()).isEqualByComparingTo("-0.52");
            assertThat(latest.orElseThrow().iv()).isEqualByComparingTo(".21");
            s.stop();
            assertThat(s.eligibleDisplayQuote("NSE_FO|61001")).isEmpty();
        } finally {
            s.shutdown();
        }
    }

    @Test
    void authorizedFreshLtpDisplaysEvenWhenOrderBookDepthIsMissing() {
        var clock = Clock.fixed(Instant.parse("2026-11-13T09:15:02Z"), ZoneOffset.UTC);
        var s = service(clock);
        try {
            s.start(List.of("NSE_FO|61001"));
            listener.get().onOpen(socket);
            listener.get().onBinary(socket, ByteBuffer.wrap(
                    UpstoxFnoV3FeedCodecTest.validFirstLevelWithoutDepth()), true);
            assertThat(s.eligiblePaperQuote("NSE_FO|61001")).isEmpty();
            var display = s.eligibleDisplayQuote("NSE_FO|61001");
            assertThat(display).isPresent();
            assertThat(display.orElseThrow().ltp()).isEqualByComparingTo("120.5");
            assertThat(display.orElseThrow().openInterest()).isEqualTo(5000L);
            assertThat(display.orElseThrow().bid()).isNull();
            assertThat(display.orElseThrow().ask()).isNull();
            assertThat(display.orElseThrow().volume()).isEqualTo(125000L);
            assertThat(display.orElseThrow().lastTradedQuantity()).isEqualTo(65L);
            assertThat(display.orElseThrow().bidQuantity()).isNull();
            assertThat(display.orElseThrow().askQuantity()).isNull();
            s.stop();
            assertThat(s.eligibleDisplayQuote("NSE_FO|61001")).isEmpty();
        } finally {
            s.shutdown();
        }
    }

    @Test
    void initialBinaryBeforeSubscriptionAckIsConsumedAndCannotBeDowngraded() {
        var s = service();
        CompletableFuture<WebSocket> delayedAck = new CompletableFuture<>();
        when(socket.sendBinary(any(ByteBuffer.class), eq(true))).thenReturn(delayedAck);
        try {
            s.start(List.of("NSE_FO|61001"));
            listener.get().onOpen(socket);
            assertThat(s.status().state()).isEqualTo("SUBSCRIBING");
            // Upstox can send initial_feed before sendBinary's future completes.
            listener.get().onBinary(socket,
                    ByteBuffer.wrap(UpstoxFnoV3FeedCodecTest.validFirstLevel()), true);
            assertThat(s.status().state()).isEqualTo("CONNECTED");
            assertThat(s.status().decodedFrames()).isEqualTo(1);
            assertThat(s.status().observedInstruments()).isEqualTo(1);
            delayedAck.complete(socket);
            assertThat(s.status().state()).isEqualTo("CONNECTED");
            assertThat(s.status().observedInstruments()).isEqualTo(1);
        } finally {
            s.shutdown();
        }
    }

    @Test
    void spotAndFutureShareOneSubscribedSocketWithoutGrantingSpotPaperOrders() {
        var closeTime=Clock.fixed(Instant.parse("2026-11-13T09:15:02Z"), ZoneOffset.UTC);
        var stream=service(closeTime);
        var spot="NSE_EQ|INE002A01018";
        try {
            stream.start(List.of("NSE_FO|61001"),List.of(spot));
            listener.get().onOpen(socket);
            var bytes=org.mockito.ArgumentCaptor.forClass(ByteBuffer.class);
            verify(socket).sendBinary(bytes.capture(),eq(true));
            var encoded=new String(bytes.getValue().array(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertThat(encoded).contains("NSE_FO|61001",spot);
            listener.get().onBinary(socket,
                ByteBuffer.wrap(UpstoxFnoV3FeedCodecTest.firstLevelFor(spot)),true);
            assertThat(stream.eligiblePaperQuote(spot)).isEmpty();
            assertThat(stream.eligibleDisplayQuote(spot)).isEmpty();
            assertThat(stream.eligibleResearchSpotQuote(spot)).isPresent();
            stream.stop();
            assertThat(stream.eligibleResearchSpotQuote(spot)).isEmpty();
        } finally {
            stream.shutdown();
        }
    }

    @Test
    void dynamicallyAddsListedContractKeysOnSameConnectedSocketWithoutOrders() {
        var current = Clock.fixed(Instant.parse("2026-11-13T09:15:02Z"), ZoneOffset.UTC);
        var s = service(current);
        try {
            s.start(List.of("NSE_FO|61001"));
            listener.get().onOpen(socket);
            listener.get().onBinary(socket,
                ByteBuffer.wrap(UpstoxFnoV3FeedCodecTest.validFirstLevel()), true);
            assertThat(s.status().state()).isEqualTo("CONNECTED");

            var result = s.subscribeAdditionalListed(List.of(
                "NSE_FO|61002", "NSE_FO|61003"));
            assertThat(result.subscriptions()).isEqualTo(3);
            assertThat(result.liveTradingEnabled()).isFalse();
            assertThat(s.hasActiveFnoSubscription("NSE_FO|61002")).isTrue();
            // A successful WebSocket send is not a real exchange-time trade.
            assertThat(s.eligibleDisplayQuote("NSE_FO|61002")).isEmpty();
            assertThat(s.eligiblePaperQuote("NSE_FO|61002")).isEmpty();
            var sent = org.mockito.ArgumentCaptor.forClass(ByteBuffer.class);
            verify(socket, times(2)).sendBinary(sent.capture(), eq(true));
            assertThat(new String(sent.getAllValues().get(1).array(),
                    java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"method\":\"sub\"",
                    "\"mode\":\"full\"",
                    "NSE_FO|61002", "NSE_FO|61003")
                .doesNotContain("NSE_FO|61001");
            // Idempotent: retries cannot duplicate provider subscriptions.
            assertThat(s.subscribeAdditionalListed(
                List.of("NSE_FO|61002")).subscriptions()).isEqualTo(3);
            verify(socket, times(2)).sendBinary(any(ByteBuffer.class), eq(true));
            verify(source, times(1)).authorizeFnoMarketFeedV3();
            verify(socket, never()).sendClose(anyInt(), anyString());
        } finally {
            s.shutdown();
        }
    }

    @Test
    void expandedCapacityStopsAt256AndNeverCreatesAnotherSocket() {
        var current = Clock.fixed(Instant.parse("2026-11-13T09:15:02Z"), ZoneOffset.UTC);
        var s = service(current);
        try {
            var initial = new java.util.ArrayList<String>();
            initial.add("NSE_FO|61001");
            for (int n = 1; n < 64; n++) initial.add("NSE_FO|" + (61001 + n));
            s.start(initial);
            listener.get().onOpen(socket);
            listener.get().onBinary(socket,
                ByteBuffer.wrap(UpstoxFnoV3FeedCodecTest.validFirstLevel()), true);
            for (int group = 1; group <= 3; group++) {
                var extra = new java.util.ArrayList<String>();
                for (int n = 0; n < 64; n++)
                    extra.add("NSE_FO|" + (61001 + group * 64 + n));
                s.subscribeAdditionalListed(extra);
            }
            assertThat(s.status().subscriptions()).isEqualTo(256);
            assertThatThrownBy(() ->
                s.subscribeAdditionalListed(List.of("NSE_FO|999999")))
                .hasMessage("UPSTOX_FNO_SUBSCRIPTION_CAP_REACHED");
            assertThat(s.status().subscriptions()).isEqualTo(256);
            verify(source, times(1)).authorizeFnoMarketFeedV3();
            verify(socket, times(4)).sendBinary(any(ByteBuffer.class), eq(true));
        } finally {
            s.shutdown();
        }
    }

    @Test
    void verifiedPlusFullCapacitySupportsOneThousandFiveHundredOnTheSameSocketOnly() {
        var current = Clock.fixed(Instant.parse("2026-11-13T09:15:02Z"), ZoneOffset.UTC);
        when(source.enabled()).thenReturn(true);
        when(source.authorizeFnoMarketFeedV3()).thenReturn(URI.create(
            "wss://prod-feed.upstox.com/market-data-feeder/v3/feeds?code=one-use"));
        when(socket.sendBinary(any(ByteBuffer.class), eq(true)))
            .thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.sendClose(anyInt(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(socket));
        var plus = new UpstoxFnoV3LiveFeedService(source, current, (uri, capture) -> {
            listener.set(capture);
            return CompletableFuture.completedFuture(socket);
        }, executor, true);
        try {
            assertThat(plus.subscriptionLimit()).isEqualTo(1500);
            var initial = new java.util.ArrayList<String>();
            for (int n = 0; n < 64; n++) initial.add("NSE_FO|" + (61001+n));
            plus.start(initial);
            listener.get().onOpen(socket);
            listener.get().onBinary(socket,
                ByteBuffer.wrap(UpstoxFnoV3FeedCodecTest.validFirstLevel()), true);
            for (int start = 64; start < 1500; start += 64) {
                var keys = new java.util.ArrayList<String>();
                for (int i = start; i < Math.min(1500, start + 64); i++)
                    keys.add("NSE_FO|" + (61001 + i));
                plus.subscribeAdditionalListed(keys);
            }
            assertThat(plus.status().subscriptions()).isEqualTo(1500);
            assertThat(plus.subscriptionLimit()).isEqualTo(1500);
            assertThat(plus.hasActiveFnoSubscription("NSE_FO|62500")).isTrue();
            assertThat(plus.eligibleDisplayQuote("NSE_FO|62500")).isEmpty();
            assertThatThrownBy(() -> plus.subscribeAdditionalListed(
                List.of("NSE_FO|62501"))).hasMessage("UPSTOX_FNO_SUBSCRIPTION_CAP_REACHED");
            verify(source, times(1)).authorizeFnoMarketFeedV3();
            verify(socket, never()).sendClose(anyInt(), anyString());
            assertThat(plus.status().liveTradingEnabled()).isFalse();
        } finally {
            plus.shutdown();
        }
    }

    @Test
    void unverifiedPlusEntitlementKeepsLegacy256RatherThanAssumingFiveSockets() {
        var legacy = service();
        try {
            assertThat(legacy.subscriptionLimit()).isEqualTo(256);
            assertThat(legacy.status().subscriptions()).isZero();
            verify(source, never()).authorizeFnoMarketFeedV3();
        } finally {
            legacy.shutdown();
        }
    }

    @Test
    void optedInPlusSupplementaryFreshQuoteRoutesThroughExistingMindAccessor() {
        var marketClock = Clock.fixed(
            Instant.parse("2026-11-13T09:15:02Z"), ZoneOffset.UTC);
        var primary = service(marketClock);
        var pool = mock(UpstoxFnoPlusMultiSocketPool.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<UpstoxFnoPlusMultiSocketPool>
            provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(pool);
        primary.setSupplementaryPoolProvider(provider);
        var key = "NSE_FO|61055";
        var exchange = marketClock.instant().minusSeconds(1);
        var display = new UpstoxFnoV3LiveFeedService.DisplayQuote(
            key, new java.math.BigDecimal("122.5"),null,10000L,
            null,null,null,null,null,
            new java.math.BigDecimal("121"),new java.math.BigDecimal("123"),
            exchange,exchange);
        var paper = new UpstoxFnoV3LiveFeedService.PaperQuote(
            key,new java.math.BigDecimal("121"),
            new java.math.BigDecimal("123"),exchange,exchange);
        when(pool.eligibleSupplementaryQuote(key)).thenReturn(
            java.util.Optional.of(display));
        when(pool.eligibleSupplementaryPaperQuote(key)).thenReturn(
            java.util.Optional.of(paper));
        when(pool.hasSupplementarySubscription(key)).thenReturn(true);
        when(pool.supplementaryCoverage()).thenReturn(
            new UpstoxFnoV3LiveFeedService.StreamingCounts(
                "SUPPLEMENTARY",2,1,1,1,1,1,0,marketClock.instant(),false));
        try {
            assertThat(primary.eligibleDisplayQuote(key)).contains(display);
            assertThat(primary.eligiblePaperQuote(key)).contains(paper);
            assertThat(primary.hasActiveFnoSubscription(key)).isTrue();
            var coverage=primary.streamingCoverage();
            assertThat(coverage.subscribedNseFno()).isEqualTo(2);
            assertThat(coverage.freshExchangeLtp()).isEqualTo(1);
            assertThat(coverage.freshTwoSidedDepth()).isEqualTo(1);
            assertThat(coverage.liveOrderAuthorized()).isFalse();
            verify(source,never()).authorizeFnoMarketFeedV3();
        } finally {
            primary.shutdown();
        }
    }

    @Test
    void dynamicSubscriptionFailsClosedWhenUnconnectedOrBinarySendFails() {
        var current = Clock.fixed(Instant.parse("2026-11-13T09:15:02Z"), ZoneOffset.UTC);
        var s = service(current);
        try {
            assertThatThrownBy(() ->
                s.subscribeAdditionalListed(List.of("NSE_FO|61002")))
                .hasMessage("UPSTOX_FNO_SOCKET_NOT_READY_FOR_ADD");
            s.start(List.of("NSE_FO|61001"));
            listener.get().onOpen(socket);
            assertThatThrownBy(() ->
                s.subscribeAdditionalListed(List.of("NSE_FO|61002")))
                .hasMessage("UPSTOX_FNO_SOCKET_NOT_READY_FOR_ADD");
            listener.get().onBinary(socket,
                ByteBuffer.wrap(UpstoxFnoV3FeedCodecTest.validFirstLevel()), true);
            when(socket.sendBinary(any(ByteBuffer.class), eq(true)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));
            assertThatThrownBy(() ->
                s.subscribeAdditionalListed(List.of("NSE_FO|61002")))
                .hasMessage("UPSTOX_FNO_SUBSCRIBE_SEND_UNVERIFIED");
            assertThat(s.hasActiveFnoSubscription("NSE_FO|61002")).isFalse();
            assertThat(s.status().subscriptions()).isEqualTo(1);
            assertThatThrownBy(() ->
                s.subscribeAdditionalListed(List.of("NSE_EQ|INE002A01018")))
                .hasMessage("UPSTOX_FNO_INVALID_LISTED_SUBSCRIPTIONS");
            assertThatThrownBy(() ->
                s.subscribeAdditionalListed(List.of("NSE_FO|61002", "NSE_FO|61002")))
                .hasMessage("UPSTOX_FNO_INVALID_LISTED_SUBSCRIPTIONS");
        } finally {
            s.shutdown();
        }
    }

    @Test
    void realtimeSummaryRequiresActualFreshExchangeTickAndNeverCountsSpot() {
        var now = Clock.fixed(Instant.parse("2026-11-13T09:15:02Z"), ZoneOffset.UTC);
        var stream = service(now);
        try {
            var stopped = stream.streamingCoverage();
            assertThat(stopped.subscribedNseFno()).isZero();
            stream.start(List.of("NSE_FO|61001"),
                List.of("NSE_EQ|INE002A01018"));
            listener.get().onOpen(socket);
            listener.get().onBinary(socket, ByteBuffer.wrap(
                UpstoxFnoV3FeedCodecTest.validFirstLevel()), true);
            var live = stream.streamingCoverage();
            assertThat(live.socketState()).isEqualTo("CONNECTED");
            assertThat(live.subscribedNseFno()).isEqualTo(1);
            assertThat(live.freshExchangeLtp()).isEqualTo(1);
            assertThat(live.freshTwoSidedDepth()).isEqualTo(1);
            assertThat(live.withOi()).isEqualTo(1);
            assertThat(live.withVolume()).isEqualTo(1);
            assertThat(live.withGreeks()).isEqualTo(1);
            assertThat(live.liveOrderAuthorized()).isFalse();
            stream.stop();
            assertThat(stream.streamingCoverage().freshExchangeLtp()).isZero();
            assertThat(stream.streamingCoverage().subscribedNseFno()).isZero();
        } finally {
            stream.shutdown();
        }
    }

    @Test
    void subscribedButNoValidTradeTimeIsCountedAsMissingNotLive() {
        var now = Clock.fixed(Instant.parse("2026-11-13T09:15:02Z"), ZoneOffset.UTC);
        var stream = service(now);
        try {
            stream.start(List.of("NSE_FO|61001", "NSE_FO|61002"));
            listener.get().onOpen(socket);
            listener.get().onBinary(socket, ByteBuffer.wrap(
                UpstoxFnoV3FeedCodecTest.validFirstLevelWithoutDepth()), true);
            var evidence = stream.streamingCoverage();
            assertThat(evidence.subscribedNseFno()).isEqualTo(2);
            assertThat(evidence.freshExchangeLtp()).isEqualTo(1);
            assertThat(evidence.withoutFreshLtp()).isEqualTo(1);
            assertThat(evidence.freshTwoSidedDepth()).isZero();
            assertThat(evidence.liveOrderAuthorized()).isFalse();
        } finally {
            stream.shutdown();
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
