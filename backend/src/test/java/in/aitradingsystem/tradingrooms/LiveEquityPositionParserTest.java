package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiveEquityPositionParserTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode data(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void angelOneIntradayLongCoversSellAndRealisedLossIsReported() throws Exception {
        var rows = LiveEquityPositionParser.angelOne(data("""
            [{"exchange":"NSE","symboltoken":"3045","producttype":"INTRADAY",
              "netqty":"10","realised":"-250.50"},
             {"exchange":"NSE","symboltoken":"2885","producttype":"DELIVERY",
              "netqty":"5","realised":"100"}]
            """));
        assertThat(rows).isNotNull().hasSize(2);
        assertThat(LiveEquityPositionParser.intradayNetQuantity(rows, "NSE", "3045")).isEqualTo(10);
        // A delivery holding never satisfies an intraday SELL.
        assertThat(LiveEquityPositionParser.intradayNetQuantity(rows, "NSE", "2885")).isZero();
        // Net day P&L is -250.50 + 100 = -150.50, a loss of 150.50.
        assertThat(LiveEquityPositionParser.realisedLoss(rows))
            .isEqualByComparingTo("150.50");
    }

    @Test
    void netLossAcrossTheDayIsReportedAsPositiveMagnitude() throws Exception {
        var rows = LiveEquityPositionParser.angelOne(data("""
            [{"exchange":"NSE","symboltoken":"3045","producttype":"INTRADAY",
              "netqty":"0","realised":"-4000.00"},
             {"exchange":"BSE","symboltoken":"500010","producttype":"INTRADAY",
              "netqty":"0","realised":"-1000.50"}]
            """));
        assertThat(LiveEquityPositionParser.realisedLoss(rows))
            .isEqualByComparingTo("5000.50");
    }

    @Test
    void shortPositionDoesNotSatisfySell() throws Exception {
        var rows = LiveEquityPositionParser.upstox(data("""
            [{"exchange":"NSE","instrument_token":"NSE_EQ|INE002A01018","product":"I",
              "quantity":-10,"realised":0}]
            """));
        assertThat(LiveEquityPositionParser.intradayNetQuantity(rows, "NSE",
            "NSE_EQ|INE002A01018")).isEqualTo(-10);
    }

    @Test
    void anyMalformedRowMakesTheWholeBookUnverifiable() throws Exception {
        assertThat(LiveEquityPositionParser.angelOne(data("""
            [{"exchange":"NSE","symboltoken":"3045","producttype":"INTRADAY",
              "netqty":"10","realised":"-1"},
             {"exchange":"MCX","symboltoken":"3045","producttype":"INTRADAY",
              "netqty":"10","realised":"0"}]
            """))).isNull();
        assertThat(LiveEquityPositionParser.angelOne(data("""
            [{"exchange":"NSE","symboltoken":"3045","producttype":"INTRADAY",
              "netqty":"ten","realised":"0"}]
            """))).isNull();
        assertThat(LiveEquityPositionParser.upstox(data("""
            [{"exchange":"NSE","instrument_token":"NSE_EQ|INE002A01018","product":"I",
              "quantity":10}]
            """))).isNull();
    }

    @Test
    void unreadableOrNonArrayBookIsNullNotEmpty() throws Exception {
        assertThat(LiveEquityPositionParser.angelOne(null)).isNull();
        assertThat(LiveEquityPositionParser.upstox(data("{\"status\":\"error\"}"))).isNull();
        assertThat(LiveEquityPositionParser.realisedLoss(null)).isNull();
        assertThat(LiveEquityPositionParser.intradayNetQuantity(null, "NSE", "3045")).isNull();
    }

    @Test
    void emptyVerifiedBookMeansFlatNotUnknown() throws Exception {
        List<LiveEquityPositionParser.Row> rows = LiveEquityPositionParser.angelOne(data("[]"));
        assertThat(rows).isNotNull().isEmpty();
        assertThat(LiveEquityPositionParser.intradayNetQuantity(rows, "NSE", "3045")).isZero();
        assertThat(LiveEquityPositionParser.realisedLoss(rows)).isEqualByComparingTo("0");
    }
}
