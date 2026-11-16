package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CustomerUpstoxFnoLiveOrderServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void brokerBookRejectsSameContractPendingAndMalformedResponses() throws Exception {
        var book = mapper.readTree("""
            [{"instrument_token":"NSE_FO|123","status":"open"},
             {"instrument_token":"NSE_FO|456","status":"complete"}]
            """);
        assertThat(CustomerUpstoxFnoLiveOrderService.hasOpenOrder(book,"NSE_FO|123"))
            .isTrue();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasOpenOrder(book,"NSE_FO|456"))
            .isFalse();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasOpenOrder(book,"NSE_FO|789"))
            .isFalse();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasOpenOrder(null,"NSE_FO|789"))
            .isTrue();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasOpenOrder(
            mapper.readTree("{}"), "NSE_FO|123")).isTrue();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasOpenOrder(
            mapper.readTree("[{\"instrument_token\":\"NSE_FO|123\"}]"),
            "NSE_FO|123")).isTrue();
    }

    @Test
    void onlyOwnerBrokerActualPositiveNfoPositionQualifiesForSellToClose() throws Exception {
        var positions = mapper.readTree("""
            [{"instrument_token":"NSE_FO|123","exchange":"NFO","product":"I","quantity":65},
             {"instrument_token":"NSE_FO|123","exchange":"NFO","product":"D","quantity":130},
             {"instrument_token":"NSE_FO|456","exchange":"NFO","product":"I","quantity":65},
             {"instrument_token":"NSE_FO|789","exchange":"NFO","product":"I","quantity":-65}]
            """);
        assertThat(CustomerUpstoxFnoLiveOrderService.availableLongUnits(
            positions,"NSE_FO|123","I")).isEqualTo(65);
        assertThat(CustomerUpstoxFnoLiveOrderService.availableLongUnits(
            positions,"NSE_FO|123","D")).isEqualTo(130);
        assertThat(CustomerUpstoxFnoLiveOrderService.availableLongUnits(
            positions,"NSE_FO|456","D")).isZero();
        assertThat(CustomerUpstoxFnoLiveOrderService.availableLongUnits(
            positions,"NSE_FO|789","I")).isZero();
        assertThat(CustomerUpstoxFnoLiveOrderService.availableLongUnits(
            mapper.readTree("{}"),"NSE_FO|123","I")).isZero();
    }
}
