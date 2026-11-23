package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxFnoInstrumentUniverse.Contract;
import in.aitradingsystem.tradingrooms.FnoFuturesContractRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class UpstoxFnoUniverseExpansionControllerTest {
    private static final String NIFTY = "NSE_INDEX|Nifty 50";
    private static final LocalDate EXPIRY = LocalDate.of(2026, 12, 1);
    private static final Instant NOW = Instant.parse("2026-11-23T08:05:00Z");
    private final FnoFuturesContractRegistry registry =
        mock(FnoFuturesContractRegistry.class);
    private final UpstoxAnalyticsSource source = mock(UpstoxAnalyticsSource.class);
    private final UpstoxFnoV3LiveFeedService socket =
        mock(UpstoxFnoV3LiveFeedService.class);
    private final MarketSessionCalendar calendar =
        mock(MarketSessionCalendar.class);

    private static UpstoxFnoV3LiveFeedService.Status status(
            String state, int subscriptions) {
        return new UpstoxFnoV3LiveFeedService.Status(state, subscriptions, 1,
            100, 0, NOW, NOW, 0, true, false, false, false, 1, NOW);
    }
    private static Contract contract(String key, String side, int strike) {
        return new Contract(key, NIFTY, "NIFTY", "NIFTY " + side,
            side, EXPIRY, BigDecimal.valueOf(strike), 65,
            new BigDecimal("5"), null, false);
    }
    private static UpstoxAnalyticsSource.FnoOptionQuote quote(String key) {
        return new UpstoxAnalyticsSource.FnoOptionQuote(key,
            new BigDecimal("130"), new BigDecimal("129"),
            new BigDecimal("131"), 10L, 5000L, 4500L,
            null, null, null, null, null);
    }
    private UpstoxFnoUniverseExpansionController subject(boolean rights) {
        return new UpstoxFnoUniverseExpansionController(
            registry, source, socket, calendar, Clock.fixed(NOW, ZoneOffset.UTC),
            rights);
    }
    private void prepared() {
        when(source.enabled()).thenReturn(true);
        when(calendar.isInsideFnoSession(NOW)).thenReturn(true);
        when(socket.status()).thenReturn(status("CONNECTED", 64));
        when(registry.universeStatus()).thenReturn(
            new FnoFuturesContractRegistry.UniverseStatus("LOADED", null,
                NOW.minusSeconds(300), "verified-master-sha",
                45451, 667, 22273, 22511, 224));
        when(registry.livePlanningCandidates(NIFTY)).thenReturn(List.of(
            contract("NSE_FO|101", "FUT", 0),
            contract("NSE_FO|201", "CE", 25600),
            contract("NSE_FO|202", "PE", 25600)));
        var strike = new UpstoxAnalyticsSource.FnoOptionStrike(EXPIRY,
            NIFTY, new BigDecimal("25600"), new BigDecimal("25620"),
            quote("NSE_FO|201"), quote("NSE_FO|202"));
        when(source.currentFnoOptionChain(NIFTY, EXPIRY)).thenReturn(
            new UpstoxAnalyticsSource.OptionChainSnapshot(
                "UPSTOX_ANALYTICS", NOW, false, List.of(strike)));
        when(socket.subscribeAdditionalListed(anyList())).thenReturn(status("CONNECTED", 67));
    }

    @Test
    void operatorCanAddOnlyRealBodVerifiedContractsWithoutRestartingSession() {
        prepared();
        var response = subject(true).expand(NIFTY, EXPIRY);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().instrumentMasterSha()).isEqualTo("verified-master-sha");
        assertThat(response.getBody().requestedListedKeys()).isEqualTo(3);
        assertThat(response.getBody().activeSubscriptions()).isEqualTo(67);
        assertThat(response.getBody().liveOrdersEnabled()).isFalse();
        verify(socket).subscribeAdditionalListed(List.of(
            "NSE_FO|101", "NSE_FO|201", "NSE_FO|202"));
        verify(socket, never()).stop();
        verify(socket, never()).start(anyList());
        verify(source, never()).authorizeFnoMarketFeedV3();
    }

    @Test
    void missingDisplayRightsBlocksEverythingBeforeBrokerOrCatalog() {
        prepared();
        assertThatThrownBy(() -> subject(false).expand(NIFTY, EXPIRY))
            .hasMessage("FNO_EXPAND_SOURCE_RIGHTS_NOT_VERIFIED");
        verify(socket, never()).subscribeAdditionalListed(anyList());
        verify(registry, never()).livePlanningCandidates(anyString());
        verify(source, never()).currentFnoOptionChain(anyString(), any());
    }

    @Test
    void failsClosedForUnloadedBodOrUnconnectedFeed() {
        prepared();
        when(registry.universeStatus()).thenReturn(
            new FnoFuturesContractRegistry.UniverseStatus("NOT_LOADED", null,
                null, null, 0, 0, 0, 0, 0));
        assertThatThrownBy(() -> subject(true).expand(NIFTY, EXPIRY))
            .hasMessage("FNO_EXPAND_CATALOG_NOT_VERIFIED");
        verify(socket, never()).subscribeAdditionalListed(anyList());

        when(socket.status()).thenReturn(status("RECONNECTING", 64));
        assertThatThrownBy(() -> subject(true).expand(NIFTY, EXPIRY))
            .hasMessage("FNO_EXPAND_SOCKET_NOT_CONNECTED");
    }

    @Test
    void incorrectUnderlyingOrExpiredContractNeverCallsProvider() {
        prepared();
        assertThatThrownBy(() -> subject(true).expand(
            "BSE_FO|malicious", EXPIRY))
            .hasMessage("FNO_UNIVERSE_EXPAND_INVALID_INPUT");
        assertThatThrownBy(() -> subject(true).expand(
            NIFTY, LocalDate.of(2026, 11, 20)))
            .hasMessage("FNO_UNIVERSE_EXPAND_INVALID_INPUT");
        verify(source, never()).currentFnoOptionChain(anyString(), any());
        verify(socket, never()).subscribeAdditionalListed(anyList());
    }

    @Test
    void repeatedOperatorClicksHitCooldownWithoutRepeatedBrokerRest() {
        prepared();
        var controller = subject(true);
        controller.expand(NIFTY, EXPIRY);
        assertThatThrownBy(() -> controller.expand(NIFTY, EXPIRY))
            .hasMessage("FNO_EXPAND_COOLDOWN");
        verify(source, times(1)).currentFnoOptionChain(NIFTY, EXPIRY);
        verify(socket, times(1)).subscribeAdditionalListed(anyList());
    }
}
