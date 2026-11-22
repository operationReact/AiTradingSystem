package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AdminFnoCustomerBoardAuditControllerTest {
    private static final String KEY = "NSE_FO|47821";
    private static final Instant NOW = Instant.parse("2026-11-22T17:55:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static CustomerFnoDiscoveryController.Capability capability(boolean enabled) {
        return new CustomerFnoDiscoveryController.Capability(
            enabled, true, enabled, false, "MARKET_CLOSED",
            "STOPPED", 0, null, enabled);
    }

    private static CustomerPaperFnoMarketController.Market market() {
        var leg = new CustomerPaperFnoMarketController.Leg(
            KEY, "NIFTY 25650 CE 01 DEC 26", "CE", 65, new BigDecimal("0.05"),
            null, null, NOW.minusSeconds(700), NOW.minusSeconds(699),
            false, false, new BigDecimal("110.25"), new BigDecimal("100"),
            2500L, null, null, null, null, null);
        var chain = new CustomerPaperFnoMarketController.ChainSnapshotIndicator(
            new BigDecimal("109.50"), null, null, 1500L,
            new BigDecimal("0.2"), new BigDecimal("0.5"),
            new BigDecimal("-3"), new BigDecimal("0.002"), new BigDecimal("5"));
        return new CustomerPaperFnoMarketController.Market(
            "NSE_INDEX|Nifty 50", LocalDate.of(2026, 12, 1),
            "UPSTOX_ANALYTICS", "MARKET_CLOSED", NOW.minusSeconds(10),
            false, true, false, false,
            List.of(new CustomerPaperFnoMarketController.Strike(
                new BigDecimal("25650"), leg, null)),
            new BigDecimal("25680"), Map.of(KEY, chain));
    }

    @Test
    void authorizedAuditReportsOnlyPresenceFromRealCustomerAssembly() {
        var discovery = mock(CustomerFnoDiscoveryController.class);
        when(discovery.internalCapability()).thenReturn(capability(true));
        when(discovery.internalMarket(LocalDate.of(2026, 12, 1))).thenReturn(market());
        var operator = new AdminFnoCustomerBoardAuditController(discovery, CLOCK);
        var response = operator.audit();
        var result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("CONTRACT_MATCHED");
        assertThat(result.boardStatus()).isEqualTo("MARKET_CLOSED");
        assertThat(result.validatedStrikes()).isEqualTo(1);
        assertThat(result.listedContractFound()).isTrue();
        assertThat(result.verifiedLastLtpPresent()).isTrue();
        assertThat(result.verifiedPreviousClosePresent()).isTrue();
        assertThat(result.verifiedLastOiPresent()).isTrue();
        assertThat(result.verifiedLastTradeTimePresent()).isTrue();
        assertThat(result.chainSnapshotLtpPresent()).isTrue();
        assertThat(result.chainSnapshotOiPresent()).isTrue();
        assertThat(result.chainSnapshotGreeksComplete()).isTrue();
        assertThat(result.chainSnapshotRecent()).isTrue();
        assertThat(result.safeNoOrders()).isTrue();
        assertThat(result.liveOrderEnabled()).isFalse();
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(result.toString()).doesNotContain("110.25", "109.50", "25680");
        assertThat(operator.audit().getBody().status()).isEqualTo("COOLDOWN");
        verify(discovery, times(1)).internalMarket(LocalDate.of(2026, 12, 1));
    }

    @Test
    void snapshotFetchedAfterAuditStartedIsNotIncorrectlyMarkedFuture() {
        var discovery = mock(CustomerFnoDiscoveryController.class);
        var clock = mock(Clock.class);
        // The audit starts before Upstox returns its option-chain response.
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(4));
        when(discovery.internalCapability()).thenReturn(capability(true));
        var original = market();
        var completedLater = new CustomerPaperFnoMarketController.Market(
            original.underlyingKey(), original.expiry(), original.source(),
            original.status(), NOW.plusSeconds(2),
            original.exchangeSpotTimeVerified(), original.displayRightsVerified(),
            original.trainingRightsVerified(), original.liveOrderEnabled(),
            original.strikes(), original.underlyingReferenceSpot(),
            original.chainSnapshotIndicators());
        when(discovery.internalMarket(LocalDate.of(2026, 12, 1)))
            .thenReturn(completedLater);
        var result = new AdminFnoCustomerBoardAuditController(discovery, clock)
            .audit().getBody();
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("CONTRACT_MATCHED");
        assertThat(result.chainSnapshotRecent()).isTrue();
        assertThat(result.chainSnapshotLtpPresent()).isTrue();
        assertThat(result.chainSnapshotOiPresent()).isTrue();
        assertThat(result.chainSnapshotGreeksComplete()).isTrue();
        verify(clock, times(2)).instant();
    }

    @Test
    void unavailableSourceFailsClosedWithoutCallingProvider() {
        var discovery = mock(CustomerFnoDiscoveryController.class);
        when(discovery.internalCapability()).thenReturn(capability(false));
        var result = new AdminFnoCustomerBoardAuditController(discovery, CLOCK)
            .audit().getBody();
        assertThat(result.status()).isEqualTo("SOURCE_OR_RIGHTS_UNAVAILABLE");
        assertThat(result.listedContractFound()).isFalse();
        verify(discovery, never()).internalMarket(any());
    }

    @Test
    void sourceRejectionIsSanitizedAndNoOrdersAreGranted() {
        var discovery = mock(CustomerFnoDiscoveryController.class);
        when(discovery.internalCapability()).thenReturn(capability(true));
        when(discovery.internalMarket(any())).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "UPSTREAM_SECRET_URL_SHOULD_NOT_LEAK"));
        var result = new AdminFnoCustomerBoardAuditController(discovery, CLOCK)
            .audit().getBody();
        assertThat(result.status()).isEqualTo("MARKET_HTTP_503");
        assertThat(result.toString()).doesNotContain("UPSTREAM_SECRET_URL");
        assertThat(result.safeNoOrders()).isTrue();
    }
}
