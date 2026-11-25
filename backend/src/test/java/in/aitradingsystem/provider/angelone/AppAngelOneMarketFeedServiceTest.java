package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.MarketDataException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The shared market-data session is never a customer-owned trading session. */
class AppAngelOneMarketFeedServiceTest {
    private final OwnerAngelOneSsmService ssm = mock(OwnerAngelOneSsmService.class);
    private final CustomerAngelOneSessionService broker = mock(CustomerAngelOneSessionService.class);
    private final CustomerAngelOneLiveQuoteService live = mock(CustomerAngelOneLiveQuoteService.class);

    private Instrument equity() {
        return new Instrument(42L, "NSE_CM", "NSE", "99999", "TEST-EQ",
            "Test", "EQ", null, null, 1,
            new BigDecimal("0.05"), UUID.randomUUID());
    }

    @Test
    void appQuotesAuthenticateFromSsmWithoutAConnectedUser() {
        when(ssm.available()).thenReturn(true);
        when(broker.status(any(UUID.class))).thenReturn(
            new CustomerAngelOneSessionService.ConnectionStatus(
                false, "ANGEL_ONE", null, null, false));
        when(ssm.connect(any(UUID.class))).thenReturn(
            new CustomerAngelOneSessionService.ConnectionStatus(
                true, "ANGEL_ONE", "***123", null, false));
        var instrument = equity();
        when(broker.quote(any(UUID.class), eq(instrument)))
            .thenReturn(new BigDecimal("121.15"));

        var service = new AppAngelOneMarketFeedService(ssm, broker, live);
        assertThat(service.quote(instrument)).isEqualByComparingTo("121.15");

        var appId = UUID.fromString("ff000000-0000-4000-8000-000000000001");
        verify(ssm).connect(appId);
        verify(broker).quote(appId, instrument);
        verifyNoInteractions(live);
    }

    @Test
    void appFeedIsUnavailableWithoutSecureSsmMode() {
        var service = new AppAngelOneMarketFeedService(ssm, broker, live);
        assertThat(service.available()).isFalse();
        assertThatThrownBy(() -> service.quote(equity()))
            .isInstanceOf(MarketDataException.class);
        verifyNoInteractions(broker, live);
        verify(ssm, never()).connect(any(UUID.class));
    }

    @Test
    void networkOrSsmFailureNeverLeaksCredentialsOrFallsBackToCustomer() {
        when(ssm.available()).thenReturn(true);
        when(broker.status(any(UUID.class))).thenReturn(
            new CustomerAngelOneSessionService.ConnectionStatus(
                false, "ANGEL_ONE", null, null, false));
        when(ssm.connect(any(UUID.class)))
            .thenThrow(new IllegalStateException("private-secret-must-not-leak"));
        var service = new AppAngelOneMarketFeedService(ssm, broker, live);
        assertThatThrownBy(() -> service.quote(equity()))
            .isInstanceOfSatisfying(MarketDataException.class, error ->
                assertThat(error.toString()).doesNotContain("private-secret-must-not-leak"));
        verifyNoInteractions(live);
    }
}
