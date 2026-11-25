package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CustomerUpstoxFnoLiveOrderServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void globalEmergencyStopBlocksNewLiveBuysButNotClosingLongPositions() {
        assertThat(CustomerUpstoxFnoLiveOrderService
            .blocksNewLiveEntry("BUY", true)).isTrue();
        assertThat(CustomerUpstoxFnoLiveOrderService
            .blocksNewLiveEntry("BUY", null)).isTrue();
        assertThat(CustomerUpstoxFnoLiveOrderService
            .blocksNewLiveEntry("BUY", false)).isFalse();
        assertThat(CustomerUpstoxFnoLiveOrderService
            .blocksNewLiveEntry("SELL", true)).isFalse();
        assertThat(CustomerUpstoxFnoLiveOrderService
            .blocksNewLiveEntry("SELL", null)).isFalse();
    }

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
    void unrelatedPendingBrokerOrdersBlockNewLiveExposure() throws Exception {
        var otherOpen = mapper.readTree("""
            [{"instrument_token":"NSE_FO|456","status":"open"},
             {"instrument_token":"NSE_FO|123","status":"complete"}]
            """);
        assertThat(CustomerUpstoxFnoLiveOrderService.hasUnsettledBrokerOrders(otherOpen))
            .isTrue();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasOpenOrder(
            otherOpen, "NSE_FO|123")).isFalse();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasUnsettledBrokerOrders(
            mapper.readTree("[{\"status\":\"complete\"},{\"status\":\"cancelled\"}]")))
            .isFalse();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasUnsettledBrokerOrders(
            mapper.readTree("[{\"status\":\"new\"}]"))).isTrue();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasUnsettledBrokerOrders(
            mapper.readTree("[{}]"))).isTrue();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasUnsettledBrokerOrders(null))
            .isTrue();
    }

    @Test
    void existingNfoExposureBlocksNewLiveBuyEvenOnDifferentContract() throws Exception {
        assertThat(CustomerUpstoxFnoLiveOrderService.hasExistingNfoExposure(
            mapper.readTree("[{\"exchange\":\"NFO\",\"quantity\":65}]"))).isTrue();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasExistingNfoExposure(
            mapper.readTree("[{\"exchange\":\"NFO\",\"quantity\":-65}]"))).isTrue();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasExistingNfoExposure(
            mapper.readTree("[{\"exchange\":\"NFO\"}]"))).isTrue();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasExistingNfoExposure(
            mapper.readTree("[{}]"))).isTrue();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasExistingNfoExposure(
            mapper.readTree("[{\"exchange\":\"NFO\",\"quantity\":0}]"))).isFalse();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasExistingNfoExposure(
            mapper.readTree("[{\"exchange\":\"NSE\",\"quantity\":3}]"))).isFalse();
        assertThat(CustomerUpstoxFnoLiveOrderService.hasExistingNfoExposure(null))
            .isTrue();
    }

    @Test
    void brokerIntradayOrderCutoffIsConservativeBeforeAutoSquareOff() {
        assertThat(CustomerUpstoxFnoLiveOrderService.insideUpstoxIntradayCutoff(
            java.time.Instant.parse("2026-11-17T09:49:59Z"), "INTRADAY")).isTrue();
        assertThat(CustomerUpstoxFnoLiveOrderService.insideUpstoxIntradayCutoff(
            java.time.Instant.parse("2026-11-17T09:50:00Z"), "INTRADAY")).isFalse();
        assertThat(CustomerUpstoxFnoLiveOrderService.insideUpstoxIntradayCutoff(
            java.time.Instant.parse("2026-11-17T10:05:00Z"), "SWING")).isTrue();
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

    private CustomerUpstoxFnoLiveOrderService.Receipt receipt() {
        return new CustomerUpstoxFnoLiveOrderService.Receipt(
            java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
            "NSE_FO|61001", java.time.LocalDate.parse("2026-11-26"), "BUY",
            65, new java.math.BigDecimal("126"), "UNKNOWN", "mt1234567890123456",
            "upstoxid123", "ACK_UNCERTAIN", java.time.Instant.parse("2026-11-20T05:00:00Z"));
    }

    private com.fasterxml.jackson.databind.JsonNode history(String events) throws Exception {
        return mapper.readTree(events);
    }

    @Test
    void fullFillIsTerminalEvenWhenBrokerReturnsOlderOpenUpdateLast() throws Exception {
        var actual=CustomerUpstoxFnoLiveOrderService.assessVerifiedBrokerHistory(
            history("""
            [{"tag":"mt1234567890123456","order_id":"upstoxid123",
              "instrument_token":"NSE_FO|61001","transaction_type":"BUY",
              "quantity":65,"filled_quantity":65,"status":"complete"},
             {"tag":"mt1234567890123456","order_id":"upstoxid123",
              "instrument_token":"NSE_FO|61001","transaction_type":"BUY",
              "quantity":65,"filled_quantity":0,"status":"open"}]
            """),receipt());
        assertThat(actual.state()).isEqualTo("COMPLETE");
        assertThat(actual.observedFilledQuantity()).isEqualTo(65);
        assertThat(actual.outcomeCode()).isEqualTo("UPSTOX_FNO_FULL_FILL_VERIFIED");
    }

    @Test
    void partialFillNeverClearsAccountWideReconciliationLock() throws Exception {
        var actual=CustomerUpstoxFnoLiveOrderService.assessVerifiedBrokerHistory(
            history("""
            [{"tag":"mt1234567890123456","order_id":"upstoxid123",
              "instrument_token":"NSE_FO|61001","transaction_type":"BUY",
              "quantity":65,"filled_quantity":14,"status":"open"}]
            """),receipt());
        assertThat(actual.state()).isEqualTo("UNKNOWN");
        assertThat(actual.outcomeCode()).isEqualTo("UPSTOX_FNO_PARTIAL_FILL_UNCERTAIN");
        assertThat(actual.observedFilledQuantity()).isEqualTo(14);
    }

    @Test
    void cancelledWithPartialIsUncertainButZeroFillIsTerminal() throws Exception {
        var partial=history("""
            [{"tag":"mt1234567890123456","order_id":"upstoxid123",
              "instrument_token":"NSE_FO|61001","transaction_type":"BUY",
              "quantity":65,"filled_quantity":12,"status":"cancelled"}]
            """);
        var zero=history("""
            [{"tag":"mt1234567890123456","order_id":"upstoxid123",
              "instrument_token":"NSE_FO|61001","transaction_type":"BUY",
              "quantity":65,"filled_quantity":0,"status":"cancelled"}]
            """);
        assertThat(CustomerUpstoxFnoLiveOrderService.assessVerifiedBrokerHistory(
            partial,receipt()).state()).isEqualTo("UNKNOWN");
        assertThat(CustomerUpstoxFnoLiveOrderService.assessVerifiedBrokerHistory(
            zero,receipt()).state()).isEqualTo("CANCELLED");
    }

    @Test
    void rejectedWithoutProofOfZeroFillsRemainsUnknown() throws Exception {
        var missingFill=history("""
            [{"tag":"mt1234567890123456","order_id":"upstoxid123",
              "instrument_token":"NSE_FO|61001","transaction_type":"BUY",
              "quantity":65,"status":"rejected"}]
            """);
        var zeroFill=history("""
            [{"tag":"mt1234567890123456","order_id":"upstoxid123",
              "instrument_token":"NSE_FO|61001","transaction_type":"BUY",
              "quantity":65,"filled_quantity":0,"status":"rejected"}]
            """);
        assertThat(CustomerUpstoxFnoLiveOrderService.assessVerifiedBrokerHistory(
            missingFill,receipt()).state()).isEqualTo("UNKNOWN");
        assertThat(CustomerUpstoxFnoLiveOrderService.assessVerifiedBrokerHistory(
            zeroFill,receipt()).state()).isEqualTo("REJECTED");
    }

    @Test
    void missingHistoryOrUnknownBrokerStateCannotClaimSuccess() throws Exception {
        assertThat(CustomerUpstoxFnoLiveOrderService.assessVerifiedBrokerHistory(
            history("[]"),receipt())).isNull();
        var result=CustomerUpstoxFnoLiveOrderService.assessVerifiedBrokerHistory(
            history("""
            [{"tag":"mt1234567890123456","order_id":"upstoxid123",
              "instrument_token":"NSE_FO|61001","transaction_type":"BUY",
              "quantity":65,"filled_quantity":0,"status":"UNRECOGNIZED"}]
            """),receipt());
        assertThat(result.state()).isEqualTo("UNKNOWN");
    }

    @Test
    void foreignTagAndBrokerIdMismatchAbortReconciliation() throws Exception {
        var foreign=history("""
            [{"tag":"anotherowner","order_id":"upstoxid123",
              "instrument_token":"NSE_FO|61001","transaction_type":"BUY",
              "quantity":65,"filled_quantity":65,"status":"complete"}]
            """);
        var wrongId=history("""
            [{"tag":"mt1234567890123456","order_id":"otherBrokerId",
              "instrument_token":"NSE_FO|61001","transaction_type":"BUY",
              "quantity":65,"filled_quantity":65,"status":"complete"}]
            """);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            CustomerUpstoxFnoLiveOrderService.assessVerifiedBrokerHistory(foreign,receipt()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            CustomerUpstoxFnoLiveOrderService.assessVerifiedBrokerHistory(wrongId,receipt()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void contradictoryTerminalEventsAndOverfillStayUncertain() throws Exception {
        var contradictions=history("""
            [{"tag":"mt1234567890123456","order_id":"upstoxid123",
              "instrument_token":"NSE_FO|61001","transaction_type":"BUY",
              "quantity":65,"filled_quantity":65,"status":"complete"},
             {"tag":"mt1234567890123456","order_id":"upstoxid123",
              "instrument_token":"NSE_FO|61001","transaction_type":"BUY",
              "quantity":65,"filled_quantity":0,"status":"rejected"}]
            """);
        var overfill=history("""
            [{"tag":"mt1234567890123456","order_id":"upstoxid123",
              "instrument_token":"NSE_FO|61001","transaction_type":"BUY",
              "quantity":65,"filled_quantity":125,"status":"complete"}]
            """);
        assertThat(CustomerUpstoxFnoLiveOrderService.assessVerifiedBrokerHistory(
            contradictions,receipt()).state()).isEqualTo("UNKNOWN");
        assertThat(CustomerUpstoxFnoLiveOrderService.assessVerifiedBrokerHistory(
            overfill,receipt()).state()).isEqualTo("UNKNOWN");
    }

}
