package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.tradingrooms.BrokerOrderRecord.BrokerReportedStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpstoxEquityOrderHistoryParserTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode data(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void parsesOneFilledEquityOrderAndMapsStatus() throws Exception {
        var records = UpstoxEquityOrderHistoryParser.parse(data("""
            [{"order_id":"250001","tag":"MTabcdefgh12","exchange":"NSE",
              "trading_symbol":"RELIANCE","transaction_type":"BUY","quantity":10,
              "price":100.0,"product":"I","order_type":"LIMIT","validity":"DAY",
              "status":"complete","filled_quantity":10,"average_price":100.25}]
            """));
        assertThat(records).hasSize(1);
        BrokerOrderRecord r = records.get(0);
        assertThat(r.brokerOrderId()).isEqualTo("250001");
        assertThat(r.tag()).isEqualTo("MTabcdefgh12");
        assertThat(r.exchange()).isEqualTo("NSE");
        assertThat(r.side()).isEqualTo("BUY");
        assertThat(r.product()).isEqualTo("INTRADAY");
        assertThat(r.orderType()).isEqualTo("LIMIT");
        assertThat(r.duration()).isEqualTo("DAY");
        assertThat(r.status()).isEqualTo(BrokerReportedStatus.FILLED);
        assertThat(r.filledQuantity()).isEqualTo(10);
    }

    @Test
    void mapsLifecycleStatusVocabulary() throws Exception {
        assertThat(parseStatus("open")).isEqualTo(BrokerReportedStatus.OPEN);
        assertThat(parseStatus("trigger pending")).isEqualTo(BrokerReportedStatus.OPEN);
        assertThat(parseStatus("rejected")).isEqualTo(BrokerReportedStatus.REJECTED);
        assertThat(parseStatus("cancelled")).isEqualTo(BrokerReportedStatus.CANCELLED);
        assertThat(parseStatus("complete")).isEqualTo(BrokerReportedStatus.FILLED);
        assertThat(parseStatus("teleported")).isEqualTo(BrokerReportedStatus.UNMAPPED);
    }

    @Test
    void nonArrayOrOversizedPayloadsYieldNoRecords() throws Exception {
        assertThat(UpstoxEquityOrderHistoryParser.parse(data("{\"not\":\"array\"}"))).isEmpty();
        assertThat(UpstoxEquityOrderHistoryParser.parse(null)).isEmpty();
    }

    @Test
    void malformedFieldsBecomeNullSoReconcilerFailsClosed() throws Exception {
        var records = UpstoxEquityOrderHistoryParser.parse(data("""
            [{"order_id":"1","tag":"MTabcdefgh12","exchange":"MCX",
              "transaction_type":"SHORT","quantity":"abc","price":"x",
              "product":"D","order_type":"MARKET","validity":"IOC","status":null}]
            """));
        BrokerOrderRecord r = records.get(0);
        assertThat(r.exchange()).isNull();
        assertThat(r.side()).isNull();
        assertThat(r.quantity()).isNull();
        assertThat(r.price()).isNull();
        assertThat(r.product()).isNull();
        assertThat(r.orderType()).isNull();
        assertThat(r.duration()).isNull();
        assertThat(r.status()).isEqualTo(BrokerReportedStatus.UNMAPPED);
    }

    private static BrokerReportedStatus parseStatus(String status) throws Exception {
        List<BrokerOrderRecord> r = UpstoxEquityOrderHistoryParser.parse(data(
            "[{\"order_id\":\"1\",\"tag\":\"MTabcdefgh12\",\"status\":\"" + status + "\"}]"));
        return r.get(0).status();
    }
}
