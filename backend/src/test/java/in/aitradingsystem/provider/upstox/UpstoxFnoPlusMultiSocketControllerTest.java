package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.tradingrooms.FnoFuturesContractRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** No network access; validates the operator source and authorization barriers. */
class UpstoxFnoPlusMultiSocketControllerTest {
    private static final Instant NOW = Instant.parse("2026-11-23T08:10:00Z");
    private final UpstoxFnoPlusMultiSocketPool pool =
        mock(UpstoxFnoPlusMultiSocketPool.class);
    private final UpstoxAnalyticsSource source = mock(UpstoxAnalyticsSource.class);
    private final FnoFuturesContractRegistry catalog =
        mock(FnoFuturesContractRegistry.class);
    private final MarketSessionCalendar calendar =
        mock(MarketSessionCalendar.class);
    private final LocalDate expiry = LocalDate.parse("2026-12-01");

    private UpstoxFnoPlusMultiSocketController subject(boolean rights) {
        return new UpstoxFnoPlusMultiSocketController(
            pool,source,catalog,calendar,rights,
            Clock.fixed(NOW,ZoneOffset.UTC));
    }

    @Test
    void customerSuppliedArbitraryContractCannotTriggerUpstreamAccess() {
        var controller=subject(true);
        assertThatThrownBy(() -> controller.expand("NSE_FO|61001",expiry))
            .hasMessage("UPSTOX_PLUS_INVALID_UNDERLYING_EXPIRY");
        verifyNoInteractions(source,catalog,calendar,pool);
    }

    @Test
    void unverifiedSourceRightsDoNotStartAnotherConnection() {
        var controller=subject(false);
        assertThatThrownBy(() -> controller.expand(
            "NSE_INDEX|Nifty 50",expiry))
            .hasMessage("UPSTOX_PLUS_SOURCE_RIGHTS_OR_SESSION_UNVERIFIED");
        verifyNoInteractions(source,catalog,calendar,pool);
    }

    @Test
    void closedExchangeBlocksAllSourceReadsAndPoolMutations() {
        var controller=subject(true);
        when(source.enabled()).thenReturn(true);
        when(calendar.isInsideFnoSession(NOW)).thenReturn(false);
        assertThatThrownBy(() -> controller.expand(
            "NSE_INDEX|Nifty 50",expiry))
            .hasMessage("UPSTOX_PLUS_SOURCE_RIGHTS_OR_SESSION_UNVERIFIED");
        verifyNoInteractions(catalog,pool);
        verify(source,never()).currentFnoOptionChain(anyString(),any());
    }

    @Test
    void unverifiedMasterFailsBeforeAnyUpstreamChainRequest() {
        var controller=subject(true);
        when(source.enabled()).thenReturn(true);
        when(calendar.isInsideFnoSession(NOW)).thenReturn(true);
        when(catalog.universeStatus()).thenReturn(
            new FnoFuturesContractRegistry.UniverseStatus(
                "STALE_SNAPSHOT",null,NOW.minusSeconds(150000),"old",
                45451,667,22273,22511,224));
        assertThatThrownBy(() -> controller.expand(
            "NSE_INDEX|Nifty 50",expiry))
            .hasMessage("UPSTOX_PLUS_BOD_NOT_VERIFIED");
        verify(source,never()).currentFnoOptionChain(anyString(),any());
        verifyNoInteractions(pool);
    }
}
