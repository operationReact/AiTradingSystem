package in.aitradingsystem.mind;

import in.aitradingsystem.marketdata.CandleSeriesValidator;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.scanner.ScanCandidate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Canonical, exact 21-bar scanner input at capture time.
 * A matching SHA-256 shows the DB rows are unchanged since this service
 * captured them. It is not an exchange signature or an independently
 * authenticated proof of original source availability.
 */
public record MindSourceSnapshot(
    String canonicalInput, String digest,
    Instant firstCandleAt, Instant lastCandleAt, Instant maxFetchedAt,
    UUID catalogueVersion, int candleCount
) {
    private static final String VERSION = "MINT_MA_RECLAIM_SOURCE_V1";
    public static final int BARS = 21;

    public static MindSourceSnapshot capture(
            List<MarketCandle> candles, ScanCandidate candidate, Instant capturedAt) {
        if (candidate == null) throw new IllegalArgumentException("Candidate is required");
        return fromCandles(candles, candidate.instrumentId(), candidate.exchange(),
            candidate.tradingSymbol(), candidate.interval(),
            candidate.candleTime(), capturedAt);
    }

    public static MindSourceSnapshot fromCandles(
            List<MarketCandle> candles, long instrumentId, String exchange,
            String symbol, String interval, Instant finalCandleAt, Instant capturedAt) {
        if (candles == null || candles.size() < BARS || instrumentId < 1 ||
            exchange == null || symbol == null || interval == null ||
            !"FIVE_MINUTE".equals(interval) ||
            finalCandleAt == null || capturedAt == null) {
            throw new IllegalArgumentException("Incomplete source capture");
        }
        // The scanner may evaluate 64 bars. Freeze precisely the last 21 bars
        // that MA_RECLAIM_V1 can observe, never unrelated future candles.
        List<MarketCandle> input = List.copyOf(candles.subList(candles.size() - BARS, candles.size()));
        if (!CandleSeriesValidator.validate(input, capturedAt, null).valid() ||
            !finalCandleAt.equals(input.getLast().candleTime())) {
            throw new IllegalArgumentException("Source candles failed chronology/cutoff checks");
        }
        UUID catalogue = input.getFirst().catalogueVersion();
        if (catalogue == null) throw new IllegalArgumentException("Missing catalogue identity");
        StringBuilder canonical = new StringBuilder(VERSION);
        Instant latestFetched = Instant.MIN;
        String source = input.getFirst().source();
        if (source == null || source.isBlank() ||
            source.matches("(?i).*(TEST|FIXTURE|DEMO|MOCK|SYNTHETIC).*")) {
            throw new IllegalArgumentException("Synthetic or missing source is not capturable");
        }
        for (MarketCandle c : input) {
            if (c.instrumentId() != instrumentId ||
                !exchange.equals(c.exchange()) || !symbol.equals(c.tradingSymbol()) ||
                !interval.equals(c.interval()) || !catalogue.equals(c.catalogueVersion()) ||
                !source.equals(c.source()) || c.symbolToken() == null || c.symbolToken().isBlank() ||
                c.fetchedAt() == null || c.fetchedAt().isAfter(capturedAt) ||
                c.candleTime().isAfter(capturedAt) ||
                c.volume() <= 0 || !validOhlc(c)) {
                throw new IllegalArgumentException("Unverifiable captured source");
            }
            if (c.fetchedAt().isAfter(latestFetched)) latestFetched = c.fetchedAt();
            append(canonical, String.valueOf(c.instrumentId()), c.exchange(), c.tradingSymbol(),
                c.symbolToken(), c.catalogueVersion().toString(), c.interval(),
                c.candleTime().toString(), decimal(c.open()), decimal(c.high()),
                decimal(c.low()), decimal(c.close()), Long.toString(c.volume()),
                c.source(), c.fetchedAt().toString());
        }
        String inputText = canonical.toString();
        return new MindSourceSnapshot(inputText, sha256(inputText),
            input.getFirst().candleTime(), input.getLast().candleTime(),
            latestFetched, catalogue, BARS);
    }

    private static boolean validOhlc(MarketCandle c) {
        return c.open() != null && c.high() != null && c.low() != null && c.close() != null &&
            c.open().signum() > 0 && c.high().signum() > 0 &&
            c.low().signum() > 0 && c.close().signum() > 0 &&
            c.high().compareTo(c.low()) >= 0 &&
            c.high().compareTo(c.open()) >= 0 && c.high().compareTo(c.close()) >= 0 &&
            c.low().compareTo(c.open()) <= 0 && c.low().compareTo(c.close()) <= 0;
    }

    private static String decimal(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }

    private static void append(StringBuilder b, String... parts) {
        b.append('\n');
        for (String part : parts) {
            // Length-prefixed fields prevent separator ambiguity.
            b.append(part.length()).append('#').append(part);
        }
    }

    private static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Digest unavailable", e);
        }
    }
}
