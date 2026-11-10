package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.instrument.Instrument;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AngelOneQuoteDetailParserTest {
    private static final Instant AVAILABLE_AT = Instant.parse("2026-11-02T10:00:00Z");
    private static final Instrument INFY = new Instrument(3, "NSE_CM", "NSE", "1594", "INFY-EQ",
            "INFOSYS", "EQ", null, null, 1, new BigDecimal("0.05"),
            UUID.fromString("12345678-1234-1234-1234-123456789abc"));

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesVerifiedFullQuoteOhlcAndVolume() throws Exception {
        var detail = AngelOneQuoteDetailParser.parse(data(row("NSE", "INFY-EQ", "1594",
                "02-Nov-2026 15:29:59", "1500.50", "1480.00", "1510.00", "1475.25", "1490.00", 123456)),
                INFY, AVAILABLE_AT);

        assertThat(detail.ltp()).isEqualByComparingTo("1500.50");
        assertThat(detail.open()).isEqualByComparingTo("1480.00");
        assertThat(detail.high()).isEqualByComparingTo("1510.00");
        assertThat(detail.low()).isEqualByComparingTo("1475.25");
        assertThat(detail.previousClose()).isEqualByComparingTo("1490.00");
        assertThat(detail.volume()).isEqualTo(123456L);
        assertThat(detail.exchangeAt()).isEqualTo(Instant.parse("2026-11-02T09:59:59Z"));
    }

    @Test
    void parsesFullQuoteDepthVwapAndFiftyTwoWeekRangeDroppingEmptyLevels() throws Exception {
        String json = """
            {"fetched":[{"exchange":"NSE","tradingSymbol":"INFY-EQ","symbolToken":"1594",
              "ltp":1500.50,"open":1480.00,"high":1510.00,"low":1475.25,"close":1490.00,
              "exchFeedTime":"02-Nov-2026 15:29:59","tradeVolume":123456,
              "avgPrice":1495.10,"52WeekHigh":1700.00,"52WeekLow":0.0,
              "totBuyQuan":5000,"totSellQuan":4000,
              "depth":{"buy":[{"price":1500.45,"quantity":300,"orders":4},
                              {"price":0.0,"quantity":0,"orders":0}],
                       "sell":[{"price":1500.55,"quantity":200,"orders":2},
                               {"price":1500.60,"quantity":1,"orders":1},
                               {"price":1500.65,"quantity":1,"orders":1},
                               {"price":1500.70,"quantity":1,"orders":1},
                               {"price":1500.75,"quantity":1,"orders":1},
                               {"price":1500.80,"quantity":1,"orders":1}]}}],
              "unfetched":[]}
            """;
        var detail = AngelOneQuoteDetailParser.parse(new ObjectMapper().readTree(json), INFY, AVAILABLE_AT);

        assertThat(detail.averagePrice()).isEqualByComparingTo("1495.10");
        assertThat(detail.week52High()).isEqualByComparingTo("1700.00");
        assertThat(detail.week52Low()).isNull();
        assertThat(detail.totalBuyQuantity()).isEqualTo(5000L);
        assertThat(detail.totalSellQuantity()).isEqualTo(4000L);
        assertThat(detail.bids()).hasSize(1);
        assertThat(detail.bids().get(0).quantity()).isEqualTo(300L);
        assertThat(detail.asks()).hasSize(5);
    }

    @Test
    void rejectsIdentityMismatch() throws Exception {
        assertRejected(row("BSE", "INFY-EQ", "1594", "02-Nov-2026 15:29:59",
                "1500", "1480", "1510", "1475", "1490", 1), "QUOTE_DETAIL_IDENTITY");
        assertRejected(row("NSE", "TCS-EQ", "1594", "02-Nov-2026 15:29:59",
                "1500", "1480", "1510", "1475", "1490", 1), "QUOTE_DETAIL_IDENTITY");
        assertRejected(row("NSE", "INFY-EQ", "9999", "02-Nov-2026 15:29:59",
                "1500", "1480", "1510", "1475", "1490", 1), "QUOTE_DETAIL_IDENTITY");
    }

    @Test
    void rejectsUnfetchedOrMultipleRows() throws Exception {
        assertRejectedNode("{\"fetched\":[],\"unfetched\":[{}]}", "QUOTE_DETAIL_COUNT");
        assertRejectedNode("{\"fetched\":[],\"unfetched\":[]}", "QUOTE_DETAIL_COUNT");
        String two = row("NSE", "INFY-EQ", "1594", "02-Nov-2026 15:29:59",
                "1500", "1480", "1510", "1475", "1490", 1);
        assertRejectedNode("{\"fetched\":[" + two + "," + two + "],\"unfetched\":[]}", "QUOTE_DETAIL_COUNT");
    }

    @Test
    void rejectsImpossibleOhlcOrder() throws Exception {
        assertRejected(row("NSE", "INFY-EQ", "1594", "02-Nov-2026 15:29:59",
                "1500", "1480", "1470", "1475", "1490", 1), "QUOTE_DETAIL_RANGE");
        assertRejected(row("NSE", "INFY-EQ", "1594", "02-Nov-2026 15:29:59",
                "1500", "1480", "1510", "1475", "1490", 1).replace("\"open\":1480", "\"open\":1600"),
                "QUOTE_DETAIL_RANGE");
    }

    @Test
    void rejectsNonPositivePricesNegativeVolumeAndFutureFeedTime() throws Exception {
        assertRejected(row("NSE", "INFY-EQ", "1594", "02-Nov-2026 15:29:59",
                "0", "1480", "1510", "1475", "1490", 1), "QUOTE_DETAIL_NON_POSITIVE");
        assertRejected(row("NSE", "INFY-EQ", "1594", "02-Nov-2026 15:29:59",
                "1500", "1480", "1510", "1475", "1490", -1), "QUOTE_DETAIL_VOLUME");
        assertRejected(row("NSE", "INFY-EQ", "1594", "02-Nov-2026 16:30:00",
                "1500", "1480", "1510", "1475", "1490", 1), "QUOTE_DETAIL_FUTURE_EVENT");
        assertRejected(row("NSE", "INFY-EQ", "1594", "not a time",
                "1500", "1480", "1510", "1475", "1490", 1), "QUOTE_DETAIL_FEED_TIME");
    }

    @Test
    void batchKeepsOnlyVerifiedRowsAndOmitsTheRest() throws Exception {
        String infy = row("NSE", "INFY-EQ", "1594", "02-Nov-2026 15:29:59",
                "1500", "1480", "1510", "1475", "1490", 10);
        String index = row("NSE", "Nifty 50", "99926000", "02-Nov-2026 15:29:59",
                "24000", "23950", "24100", "23900", "23980", 0);
        String wrongSymbol = row("NSE", "TCS-EQ", "1594", "02-Nov-2026 15:29:59",
                "1500", "1480", "1510", "1475", "1490", 1);
        String badRange = row("NSE", "INFY-EQ", "1594", "02-Nov-2026 15:29:59",
                "1500", "1480", "1470", "1475", "1490", 1);
        String wrongIndexName = row("NSE", "Nifty Bank", "99926013", "02-Nov-2026 15:29:59",
                "24000", "23950", "24100", "23900", "23980", 0);
        JsonNode node = mapper.readTree("{\"fetched\":[" + infy + "," + index + "," + wrongSymbol + ","
                + badRange + "," + wrongIndexName + "],\"unfetched\":[]}");

        var result = AngelOneQuoteDetailParser.parseBatch(node, java.util.List.of(
                new AngelOneQuoteDetailParser.Expected("1594", "INFY-EQ"),
                new AngelOneQuoteDetailParser.Expected("99926000", "Nifty 50"),
                new AngelOneQuoteDetailParser.Expected("99926013", "Nifty Next 50")), AVAILABLE_AT);

        assertThat(result).containsOnlyKeys("1594", "99926000");
        assertThat(result.get("1594").ltp()).isEqualByComparingTo("1500");
        assertThat(result.get("99926000").previousClose()).isEqualByComparingTo("23980");
    }

    @Test
    void indexFullQuoteCanOmitShareVolumeWithoutBeingDropped() throws Exception {
        // Real index FULL replies are published without a traded-share volume.
        // They must still carry a true, nonfuture exchange timestamp and OHLC.
        String index = row("NSE", "Nifty 50", "99926000", "02-Nov-2026 15:29:59",
                "24000", "23950", "24100", "23900", "23980", 0)
                .replace(",\"tradeVolume\":0", "");
        var expected = java.util.List.of(
            new AngelOneQuoteDetailParser.Expected("99926000", null));
        var valid = AngelOneQuoteDetailParser.parseBatch(data(index), expected, AVAILABLE_AT);
        assertThat(valid).containsOnlyKeys("99926000");
        assertThat(valid.get("99926000").volume()).isZero();
        assertThat(valid.get("99926000").exchangeAt())
            .isEqualTo(Instant.parse("2026-11-02T09:59:59Z"));

        var invalid = AngelOneQuoteDetailParser.parseBatch(
            data(index.replace("02-Nov-2026 15:29:59", "02-Nov-2026 16:30:00")),
            expected, AVAILABLE_AT);
        assertThat(invalid).isEmpty();
        var negative = AngelOneQuoteDetailParser.parseBatch(
            data(index.replace("}", ",\"tradeVolume\":-1}")),
            expected, AVAILABLE_AT);
        assertThat(negative).isEmpty();
    }

    @Test
    void equityFullQuoteMissingVolumeIsStillRejected() throws Exception {
        String equity = row("NSE", "INFY-EQ", "1594", "02-Nov-2026 15:29:59",
                "1500", "1480", "1510", "1475", "1490", 0)
                .replace(",\"tradeVolume\":0", "");
        var parsed = AngelOneQuoteDetailParser.parseBatch(data(equity),
            java.util.List.of(new AngelOneQuoteDetailParser.Expected("1594", "INFY-EQ")),
            AVAILABLE_AT);
        assertThat(parsed).isEmpty();
    }

    @Test
    void batchWithNoFetchedRowsIsEmptyButMalformedShapeIsRejected() throws Exception {
        var empty = AngelOneQuoteDetailParser.parseBatch(
                mapper.readTree("{\"fetched\":[],\"unfetched\":[{}]}"),
                java.util.List.of(new AngelOneQuoteDetailParser.Expected("1594", "INFY-EQ")), AVAILABLE_AT);
        assertThat(empty).isEmpty();

        JsonNode malformed = mapper.readTree("{\"unfetched\":[]}");
        assertThatThrownBy(() -> AngelOneQuoteDetailParser.parseBatch(malformed,
                java.util.List.of(new AngelOneQuoteDetailParser.Expected("1594", "INFY-EQ")), AVAILABLE_AT))
                .isInstanceOfSatisfying(AngelOneException.class,
                        ex -> assertThat(ex.code()).isEqualTo("QUOTE_BATCH_SHAPE"));
    }

    private void assertRejected(String rowJson, String code) throws Exception {
        assertRejectedNode(data(rowJson).toString(), code);
    }

    private void assertRejectedNode(String json, String code) throws Exception {
        JsonNode node = mapper.readTree(json);
        assertThatThrownBy(() -> AngelOneQuoteDetailParser.parse(node, INFY, AVAILABLE_AT))
                .isInstanceOfSatisfying(AngelOneException.class,
                        ex -> assertThat(ex.code()).isEqualTo(code));
    }

    private JsonNode data(String rowJson) throws Exception {
        return mapper.readTree("{\"fetched\":[" + rowJson + "],\"unfetched\":[]}");
    }

    private static String row(String exchange, String symbol, String token, String feedTime,
            String ltp, String open, String high, String low, String close, long volume) {
        return "{\"exchange\":\"" + exchange + "\",\"tradingSymbol\":\"" + symbol
                + "\",\"symbolToken\":\"" + token + "\",\"exchFeedTime\":\"" + feedTime
                + "\",\"ltp\":" + ltp + ",\"open\":" + open + ",\"high\":" + high
                + ",\"low\":" + low + ",\"close\":" + close + ",\"tradeVolume\":" + volume + "}";
    }
}
