package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpstoxFnoV3FeedCodecTest {
    private static final long TRADE_MS = Instant.parse("2026-11-13T09:15:00Z").toEpochMilli();
    private static final String KEY = "NSE_FO|61001";

    @Test
    void decodesActualV3FirstLevelWithGreeksWireNumbersNotJson() {
        var tick = UpstoxFnoV3FeedCodec.decode(validFirstLevel());
        assertThat(tick.feedAt()).isEqualTo(Instant.ofEpochMilli(TRADE_MS + 1000));
        assertThat(tick.ticks()).hasSize(1);
        var x = tick.ticks().getFirst();
        assertThat(x.instrumentKey()).isEqualTo(KEY);
        assertThat(x.tradedAt()).isEqualTo(Instant.ofEpochMilli(TRADE_MS));
        assertThat(x.ltp()).isEqualByComparingTo("120.5");
        assertThat(x.bid()).isEqualByComparingTo("119.5");
        assertThat(x.ask()).isEqualByComparingTo("121");
        assertThat(x.openInterest()).isEqualTo(5000L);
        assertThat(x.iv()).isEqualByComparingTo("0.21");
        assertThat(x.delta()).isEqualByComparingTo("-0.52");
        assertThat(x.theta()).isEqualByComparingTo("-2.1");
        assertThat(x.gamma()).isEqualByComparingTo("0.001");
        assertThat(x.vega()).isEqualByComparingTo("4.2");
    }

    @Test
    void decodesMarketFullFeedWithTopDepthAndOpenInterest() {
        Proto ltpc = new Proto().d(1, 125.1).v(2, TRADE_MS);
        Proto depth = new Proto().m(1, new Proto().d(2, 125).d(4, 126));
        Proto greeks = new Proto().d(1, 0.4).d(2, -3.5).d(3, 0.002).d(4, 5.1);
        Proto marketFF = new Proto().m(1, ltpc).m(2, depth).m(3, greeks)
                .d(7, 4001).d(8, .18);
        Proto f = new Proto().m(2, new Proto().m(1, marketFF));
        var x = UpstoxFnoV3FeedCodec.decode(
                new Proto().m(2, new Proto().s(1, KEY).m(2, f)).v(3, TRADE_MS + 200).bytes())
                .ticks().getFirst();
        assertThat(x.ltp()).isEqualByComparingTo("125.1");
        assertThat(x.bid()).isEqualByComparingTo("125");
        assertThat(x.ask()).isEqualByComparingTo("126");
        assertThat(x.openInterest()).isEqualTo(4001);
        assertThat(x.iv()).isEqualByComparingTo(".18");
        assertThat(x.delta()).isEqualByComparingTo(".4");
    }

    @Test
    void subscriptionUsesBinaryCompatibleJsonWithV3FullMode() {
        var bytes = UpstoxFnoV3FeedCodec.subscription("sub", "full",
                List.of(KEY, "NSE_FO|61002"));
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertThat(json).contains("\"method\":\"sub\"", "\"mode\":\"full\"",
                "\"instrumentKeys\":[\"NSE_FO|61001\",\"NSE_FO|61002\"]");
        assertThat(json).contains("\"guid\":\"");
        assertThatThrownBy(() -> UpstoxFnoV3FeedCodec.subscription(
                "sub", "option_chain", List.of(KEY)))
                .hasMessage("UPSTOX_FNO_INVALID_SUBSCRIPTION"); // V2 mode discontinued
        assertThatThrownBy(() -> UpstoxFnoV3FeedCodec.subscription(
                "sub", "full", List.of(KEY, KEY)))
                .hasMessage("UPSTOX_FNO_INVALID_SUBSCRIPTION");
        assertThatThrownBy(() -> UpstoxFnoV3FeedCodec.subscription(
                "sub", "full", List.of("NSE_FO|123\"} malicious")))
                .hasMessage("UPSTOX_FNO_INVALID_SUBSCRIPTION");
    }

    @Test
    void invalidFramesAndNonFiniteGreeksFailClosed() {
        assertThatThrownBy(() -> UpstoxFnoV3FeedCodec.decode(new byte[0]))
                .hasMessage("UPSTOX_FNO_INVALID_BINARY_FEED");
        assertThatThrownBy(() -> UpstoxFnoV3FeedCodec.decode(
                "{\"ltp\":123}".getBytes(StandardCharsets.UTF_8)))
                .hasMessage("UPSTOX_FNO_INVALID_BINARY_FEED");
        assertThatThrownBy(() -> UpstoxFnoV3FeedCodec.decode(new byte[1024 * 1024 + 1]))
                .hasMessage("UPSTOX_FNO_INVALID_BINARY_FEED");
        Proto invalid = new Proto().m(2, new Proto().s(1, KEY)
                .m(2, new Proto().m(3, new Proto()
                        .m(3, new Proto().d(1, Double.NaN)))));
        assertThatThrownBy(() -> UpstoxFnoV3FeedCodec.decode(invalid.bytes()))
                .hasMessage("UPSTOX_FNO_INVALID_BINARY_FEED");
    }

    @Test
    void absentTradeTimestampIsNeverFabricatedFromFeedTimestamp() {
        var frame = new Proto().m(2, new Proto().s(1, KEY)
                .m(2, new Proto().m(1, new Proto().d(1, 120.5))))
                .v(3, TRADE_MS + 100).bytes();
        var tick = UpstoxFnoV3FeedCodec.decode(frame).ticks().getFirst();
        assertThat(tick.tradedAt()).isNull();
        assertThat(tick.feedAt()).isEqualTo(Instant.ofEpochMilli(TRADE_MS + 100));
    }

    @Test
    void nonFoKeysAreNotPublishedEvenIfPresentInStream() {
        var frame = new Proto().m(2, new Proto().s(1, "NSE_EQ|INE002A01018")
                .m(2, new Proto().m(1, new Proto().d(1, 100)))).bytes();
        assertThat(UpstoxFnoV3FeedCodec.decode(frame).ticks()).isEmpty();
    }

    static byte[] validFirstLevel() {
        Proto ltpc = new Proto().d(1, 120.5).v(2, TRADE_MS).v(3, 65).d(4, 130);
        Proto firstDepth = new Proto().v(1, 100).d(2, 119.5).v(3, 200).d(4, 121);
        Proto greeks = new Proto().d(1, -0.52).d(2, -2.1).d(3, .001).d(4, 4.2);
        Proto first = new Proto().m(1, ltpc).m(2, firstDepth).m(3, greeks)
                .d(5, 5000).d(6, .21);
        return new Proto().m(2, new Proto().s(1, KEY).m(2, new Proto().m(3, first)))
                .v(3, TRADE_MS + 1000).bytes();
    }

    /** Tiny deterministic test encoder for the documented V3 wire schema. */
    private static final class Proto {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Proto v(int field, long value) {
            varint((field << 3));
            varint(value);
            return this;
        }
        Proto d(int field, double value) {
            varint((field << 3) | 1);
            byte[] eight = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .putDouble(value).array();
            bytes.writeBytes(eight);
            return this;
        }
        Proto s(int field, String text) {
            raw(field, text.getBytes(StandardCharsets.UTF_8));
            return this;
        }
        Proto m(int field, Proto message) {
            raw(field, message.bytes());
            return this;
        }
        void raw(int field, byte[] data) {
            varint((field << 3) | 2);
            varint(data.length);
            bytes.writeBytes(data);
        }
        void varint(long n) {
            while ((n & ~127L) != 0) {
                bytes.write(((int) n & 127) | 128);
                n >>>= 7;
            }
            bytes.write((int) n);
        }
        byte[] bytes() { return bytes.toByteArray(); }
    }
}
