package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.tradingrooms.BrokerOrderRecord.BrokerReportedStatus;
import org.junit.jupiter.api.Test;

class AngelOneEquityOrderBookParserTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode data(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void parsesOneOrderWithStringNumbersAndSuffixedSymbol() throws Exception {
        var records = AngelOneEquityOrderBookParser.parse(data("""
            [{"orderid":"2410010001","ordertag":"MTabcdefgh12","exchange":"NSE",
              "tradingsymbol":"SBIN-EQ","transactiontype":"BUY","quantity":"5",
              "price":"550.25","producttype":"INTRADAY","ordertype":"LIMIT",
              "duration":"DAY","orderstatus":"open","filledshares":"2",
              "averageprice":"550.20"}]
            """));
        assertThat(records).hasSize(1);
        BrokerOrderRecord r = records.get(0);
        assertThat(r.brokerOrderId()).isEqualTo("2410010001");
        assertThat(r.tag()).isEqualTo("MTabcdefgh12");
        assertThat(r.tradingSymbol()).isEqualTo("SBIN-EQ");
        assertThat(r.side()).isEqualTo("BUY");
        assertThat(r.quantity()).isEqualTo(5);
        assertThat(r.product()).isEqualTo("INTRADAY");
        assertThat(r.status()).isEqualTo(BrokerReportedStatus.OPEN);
        assertThat(r.filledQuantity()).isEqualTo(2);
    }

    @Test
    void mapsStatusVocabularyIncludingCancelAfterMarket() throws Exception {
        assertThat(parseStatus("complete")).isEqualTo(BrokerReportedStatus.FILLED);
        assertThat(parseStatus("rejected")).isEqualTo(BrokerReportedStatus.REJECTED);
        assertThat(parseStatus("cancelled")).isEqualTo(BrokerReportedStatus.CANCELLED);
        assertThat(parseStatus("cancelled after market order"))
            .isEqualTo(BrokerReportedStatus.CANCELLED);
        assertThat(parseStatus("trigger pending")).isEqualTo(BrokerReportedStatus.OPEN);
        assertThat(parseStatus("weird")).isEqualTo(BrokerReportedStatus.UNMAPPED);
    }

    @Test
    void nonArrayYieldsNoRecords() throws Exception {
        assertThat(AngelOneEquityOrderBookParser.parse(data("{}"))).isEmpty();
        assertThat(AngelOneEquityOrderBookParser.parse(null)).isEmpty();
    }

    @Test
    void malformedNumbersBecomeNull() throws Exception {
        var records = AngelOneEquityOrderBookParser.parse(data("""
            [{"orderid":"1","ordertag":"MTabcdefgh12","quantity":"-5","price":"NaN",
              "filledshares":"999999999"}]
            """));
        BrokerOrderRecord r = records.get(0);
        assertThat(r.quantity()).isNull();
        assertThat(r.price()).isNull();
        assertThat(r.filledQuantity()).isNull();
    }

    private static BrokerReportedStatus parseStatus(String status) throws Exception {
        var r = AngelOneEquityOrderBookParser.parse(data(
            "[{\"orderid\":\"1\",\"ordertag\":\"MTabcdefgh12\",\"orderstatus\":\""
                + status + "\"}]"));
        return r.get(0).status();
    }
}
