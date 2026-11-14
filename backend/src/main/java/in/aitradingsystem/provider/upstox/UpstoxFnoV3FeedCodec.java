package in.aitradingsystem.provider.upstox;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Small, bounded reader for Upstox MarketDataFeedV3.proto. Uses the official
 * V3 protobuf field numbers, without an extra protoc toolchain/runtime dependency.
 * Only decodes F&O LTPC, first depth, OI, IV and Greeks. Never decodes JSON ticks.
 * Unknown protobuf fields are safely skipped for forward compatibility.
 */
final class UpstoxFnoV3FeedCodec {
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final int MAX_FEEDS = 128;

    record Tick(String instrumentKey, Instant tradedAt, Instant feedAt,
                BigDecimal ltp, BigDecimal bid, BigDecimal ask, Long openInterest,
                BigDecimal iv, BigDecimal delta, BigDecimal theta,
                BigDecimal gamma, BigDecimal vega) {}

    record FeedFrame(Instant feedAt, List<Tick> ticks) {}

    private UpstoxFnoV3FeedCodec() {}

    /** Upstox V3 subscription commands are binary UTF-8 JSON, NOT text frames. */
    static byte[] subscription(String method, String mode, List<String> keys) {
        if (!("sub".equals(method) || "unsub".equals(method)
                || "change_mode".equals(method))
                || !("full".equals(mode) || "ltpc".equals(mode)
                || "option_greeks".equals(mode))
                || keys == null || keys.isEmpty() || keys.size() > 64
                || keys.stream().anyMatch(k -> !validKey(k))
                || keys.stream().distinct().count() != keys.size()) {
            throw new IllegalArgumentException("UPSTOX_FNO_INVALID_SUBSCRIPTION");
        }
        StringBuilder out = new StringBuilder(256 + keys.size() * 30);
        out.append("{\"guid\":\"").append(UUID.randomUUID())
                .append("\",\"method\":\"").append(method)
                .append("\",\"data\":{\"mode\":\"").append(mode)
                .append("\",\"instrumentKeys\":[");
        for (int i = 0; i < keys.size(); i++) {
            if (i != 0) out.append(',');
            // Keys are strictly alphanumeric/dash/underscore with FO prefix.
            out.append('"').append(keys.get(i)).append('"');
        }
        return out.append("]}}").toString().getBytes(StandardCharsets.UTF_8);
    }

    static boolean validKey(String key) {
        return key != null && key.matches("(NSE_FO|BSE_FO)\\|[A-Za-z0-9._-]{1,64}");
    }

    static FeedFrame decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("UPSTOX_FNO_INVALID_BINARY_FEED");
        }
        try {
            Wire root = new Wire(ByteBuffer.wrap(bytes));
            Instant feedAt = null;
            List<KeyFeed> keyed = new ArrayList<>();
            while (root.more()) {
                int tag = root.tag();
                switch (tag >>> 3) {
                    case 2 -> {
                        if ((tag & 7) != 2 || keyed.size() >= MAX_FEEDS)
                            throw new IllegalArgumentException();
                        keyed.add(parseMap(root.message()));
                    }
                    case 3 -> {
                        if ((tag & 7) != 0) throw new IllegalArgumentException();
                        feedAt = epochMillis(root.varint());
                    }
                    default -> root.skip(tag);
                }
            }
            List<Tick> ticks = new ArrayList<>();
            for (KeyFeed f : keyed) {
                if (validKey(f.key) && f.quote != null) {
                    Quote q = f.quote;
                    ticks.add(new Tick(f.key, q.tradedAt, feedAt, q.ltp, q.bid, q.ask,
                            q.oi, q.iv, q.delta, q.theta, q.gamma, q.vega));
                }
            }
            return new FeedFrame(feedAt, List.copyOf(ticks));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("UPSTOX_FNO_INVALID_BINARY_FEED");
        }
    }

    private record KeyFeed(String key, Quote quote) {}

    private static KeyFeed parseMap(Wire w) {
        String key = null;
        Quote feed = null;
        while (w.more()) {
            int t = w.tag();
            switch (t >>> 3) {
                case 1 -> { if ((t & 7) != 2) throw new IllegalArgumentException(); key = w.string(); }
                case 2 -> { if ((t & 7) != 2) throw new IllegalArgumentException(); feed = parseFeed(w.message()); }
                default -> w.skip(t);
            }
        }
        return new KeyFeed(key, feed);
    }

    private static Quote parseFeed(Wire w) {
        Quote quote = new Quote();
        while (w.more()) {
            int t = w.tag();
            switch (t >>> 3) {
                case 1 -> { if ((t & 7) != 2) throw new IllegalArgumentException(); parseLtpc(w.message(), quote); }
                case 2 -> { if ((t & 7) != 2) throw new IllegalArgumentException(); parseFullFeed(w.message(), quote); }
                case 3 -> { if ((t & 7) != 2) throw new IllegalArgumentException(); parseFirstLevel(w.message(), quote); }
                default -> w.skip(t);
            }
        }
        return quote;
    }

    private static void parseFullFeed(Wire w, Quote q) {
        while (w.more()) {
            int t = w.tag();
            if ((t >>> 3) == 1 && (t & 7) == 2) parseMarketFull(w.message(), q);
            else w.skip(t); // Index full feed is not an F&O contract.
        }
    }

    private static void parseMarketFull(Wire w, Quote q) {
        while (w.more()) {
            int t = w.tag();
            switch (t >>> 3) {
                case 1 -> { if ((t & 7) != 2) throw new IllegalArgumentException(); parseLtpc(w.message(), q); }
                case 2 -> { if ((t & 7) != 2) throw new IllegalArgumentException(); parseDepth(w.message(), q); }
                case 3 -> { if ((t & 7) != 2) throw new IllegalArgumentException(); parseGreeks(w.message(), q); }
                case 7 -> { if ((t & 7) != 1) throw new IllegalArgumentException(); q.oi = nonnegativeLong(w.decimal()); }
                case 8 -> { if ((t & 7) != 1) throw new IllegalArgumentException(); q.iv = nonnegative(w.decimal()); }
                default -> w.skip(t);
            }
        }
    }

    private static void parseFirstLevel(Wire w, Quote q) {
        while (w.more()) {
            int t = w.tag();
            switch (t >>> 3) {
                case 1 -> { if ((t & 7) != 2) throw new IllegalArgumentException(); parseLtpc(w.message(), q); }
                case 2 -> { if ((t & 7) != 2) throw new IllegalArgumentException(); parseTopQuote(w.message(), q); }
                case 3 -> { if ((t & 7) != 2) throw new IllegalArgumentException(); parseGreeks(w.message(), q); }
                case 5 -> { if ((t & 7) != 1) throw new IllegalArgumentException(); q.oi = nonnegativeLong(w.decimal()); }
                case 6 -> { if ((t & 7) != 1) throw new IllegalArgumentException(); q.iv = nonnegative(w.decimal()); }
                default -> w.skip(t);
            }
        }
    }

    private static void parseLtpc(Wire w, Quote q) {
        while (w.more()) {
            int t = w.tag();
            switch (t >>> 3) {
                case 1 -> { if ((t & 7) != 1) throw new IllegalArgumentException(); q.ltp = nonnegative(w.decimal()); }
                case 2 -> { if ((t & 7) != 0) throw new IllegalArgumentException(); q.tradedAt = epochMillis(w.varint()); }
                default -> w.skip(t);
            }
        }
    }

    private static void parseDepth(Wire w, Quote q) {
        // First market depth level only; parsing many levels is unnecessary.
        boolean first = true;
        while (w.more()) {
            int t = w.tag();
            if ((t >>> 3) == 1 && (t & 7) == 2) {
                Wire quote = w.message();
                if (first) parseTopQuote(quote, q);
                first = false;
            } else w.skip(t);
        }
    }

    private static void parseTopQuote(Wire w, Quote q) {
        while (w.more()) {
            int t = w.tag();
            switch (t >>> 3) {
                case 2 -> { if ((t & 7) != 1) throw new IllegalArgumentException(); q.bid = nonnegative(w.decimal()); }
                case 4 -> { if ((t & 7) != 1) throw new IllegalArgumentException(); q.ask = nonnegative(w.decimal()); }
                default -> w.skip(t);
            }
        }
    }

    private static void parseGreeks(Wire w, Quote q) {
        while (w.more()) {
            int t = w.tag();
            if ((t & 7) == 1 && (t >>> 3) >= 1 && (t >>> 3) <= 5) {
                BigDecimal value = w.decimal();
                switch (t >>> 3) {
                    case 1 -> q.delta = value;
                    case 2 -> q.theta = value;
                    case 3 -> q.gamma = value;
                    case 4 -> q.vega = value;
                    default -> { /* rho not used in initial pilot */ }
                }
            } else w.skip(t);
        }
    }

    private static BigDecimal nonnegative(BigDecimal v) {
        if (v.signum() < 0) throw new IllegalArgumentException();
        return v;
    }
    private static Long nonnegativeLong(BigDecimal v) {
        if (v.signum() < 0) throw new IllegalArgumentException();
        return v.longValueExact();
    }
    private static Instant epochMillis(long value) {
        if (value <= 0) return null; // Missing timestamp is never fresh.
        return Instant.ofEpochMilli(value);
    }

    private static final class Quote {
        Instant tradedAt;
        BigDecimal ltp, bid, ask, iv, delta, theta, gamma, vega;
        Long oi;
    }

    /** Minimal protobuf wire cursor, length- and tag-bounded, with no reflection. */
    private static final class Wire {
        private final ByteBuffer b;
        Wire(ByteBuffer b) { this.b = b.order(ByteOrder.LITTLE_ENDIAN); }
        boolean more() { return b.hasRemaining(); }
        int tag() {
            long t = varint();
            if (t == 0 || (t >>> 3) == 0 || t > Integer.MAX_VALUE)
                throw new IllegalArgumentException();
            return (int) t;
        }
        long varint() {
            long out = 0;
            for (int i = 0; i < 10; i++) {
                int v = b.get() & 255;
                if (i == 9 && v > 1) throw new IllegalArgumentException();
                out |= (long) (v & 127) << (7 * i);
                if ((v & 128) == 0) return out;
            }
            throw new IllegalArgumentException();
        }
        Wire message() {
            long n = varint();
            if (n < 0 || n > b.remaining()) throw new IllegalArgumentException();
            ByteBuffer sub = b.slice().order(ByteOrder.LITTLE_ENDIAN);
            sub.limit((int) n);
            b.position(b.position() + (int) n);
            return new Wire(sub);
        }
        String string() {
            Wire v = message();
            if (v.b.remaining() > 128) throw new IllegalArgumentException();
            return StandardCharsets.UTF_8.decode(v.b).toString();
        }
        BigDecimal decimal() {
            double v = b.getDouble();
            if (!Double.isFinite(v)) throw new IllegalArgumentException();
            return BigDecimal.valueOf(v);
        }
        void skip(int tag) {
            switch (tag & 7) {
                case 0 -> varint();
                case 1 -> { if (b.remaining() < 8) throw new IllegalArgumentException(); b.position(b.position() + 8); }
                case 2 -> message();
                case 5 -> { if (b.remaining() < 4) throw new IllegalArgumentException(); b.position(b.position() + 4); }
                default -> throw new IllegalArgumentException();
            }
        }
    }
}
