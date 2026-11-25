package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CustomerPortfolioReadControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalizesBrokerRowsWithoutClaimingMintTickExecutedThem() throws Exception {
        JsonNode raw = mapper.readTree("""
            {"data":{"holdings":[
              {"tradingsymbol":"RELIANCE-EQ","quantity":"3","ltp":"1543.25",
               "averageprice":"1200.00","pnl":"1029.75"},
              {"trading_symbol":"SBIN","quantity":2,"last_price":670.5}
            ]}}
            """);
        var rows = CustomerPortfolioReadController.normalize(
            CustomerPortfolioReadController.recordsNode(raw, "HOLDINGS"), false);
        assertEquals(2, rows.size());
        assertEquals("RELIANCE-EQ", rows.get(0).symbol());
        assertEquals(new BigDecimal("4629.75"), rows.get(0).marketValue());
        assertEquals("BROKER_REPORTED", rows.get(0).provenance());
        assertEquals(new BigDecimal("1341.00"), rows.get(1).marketValue());
    }

    @Test
    void unknownProviderMetricsRemainUnavailableNotZero() throws Exception {
        JsonNode raw = mapper.readTree("""
            [{"tradingsymbol":"INFY","quantity":"abc","ltp":"not-price"},
             {"tradingsymbol":"TCS","quantity":0}]
            """);
        var rows = CustomerPortfolioReadController.normalize(raw, false);
        assertEquals(2, rows.size());
        assertNull(rows.get(0).quantity());
        assertNull(rows.get(0).marketValue());
        assertNull(rows.get(1).marketValue());
    }

    @Test
    void malformedNonemptyProviderDataDoesNotProduceFalseEmptyAccount() throws Exception {
        JsonNode bad = mapper.readTree("""
            [{"unrelated_field":"not-a-holding"}]
            """);
        assertThrows(IllegalArgumentException.class,
            () -> CustomerPortfolioReadController.normalize(bad, false));
        JsonNode partial = mapper.readTree("""
            [{"trading_symbol":"SBIN","quantity":1}, {"unexpected":"shape"}]
            """);
        assertThrows(IllegalArgumentException.class,
            () -> CustomerPortfolioReadController.normalize(partial, false));
        assertTrue(CustomerPortfolioReadController.normalize(
            mapper.readTree("[]"), false).isEmpty());
    }

    @Test
    void doesNotComputeDerivativePositionValueOrClaimOrderAttribution() throws Exception {
        JsonNode raw = mapper.readTree("""
            [{"tradingsymbol":"NIFTY26NOVFUT","netqty":-25,"ltp":225.5}]
            """);
        var rows = CustomerPortfolioReadController.normalize(raw, true);
        assertEquals(new BigDecimal("-25"), rows.get(0).quantity());
        assertNull(rows.get(0).marketValue());
        assertEquals("BROKER_REPORTED", rows.get(0).provenance());
        assertNull(CustomerPortfolioReadController.recordsNode(
            mapper.readTree("{\"unknown\":true}"), "POSITIONS"));
    }
}
