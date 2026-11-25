package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.aitradingsystem.marketdata.MarketDataException;
import in.aitradingsystem.instrument.Instrument;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Inside the feed's backoff window the caller still sees the real reason code, not a generic one. */
class AppAngelOneBackoffReasonTest {

    private static final Instrument RELIANCE = new Instrument(417378L, "NSE_EQ", "NSE", "2885", "RELIANCE-EQ",
            "RELIANCE INDUSTRIES", "EQ", (LocalDate) null, (BigDecimal) null, 1, new BigDecimal("0.05"), UUID.randomUUID());

    @Test
    void backoffWindowKeepsTheFirstStableReasonCode() {
        OwnerAngelOneSsmService ssm = mock(OwnerAngelOneSsmService.class);
        CustomerAngelOneSessionService sessions = mock(CustomerAngelOneSessionService.class);
        CustomerAngelOneLiveQuoteService quotes = mock(CustomerAngelOneLiveQuoteService.class);
        CustomerAngelOneSessionService.ConnectionStatus status = mock(CustomerAngelOneSessionService.ConnectionStatus.class);
        when(ssm.available()).thenReturn(true);
        when(sessions.status(any())).thenReturn(status);
        when(status.connected()).thenReturn(false);
        when(ssm.connect(any())).thenThrow(new AngelOneException(AngelOneException.Kind.NOT_CONFIGURED,
                "OWNER_SSM_ACCESS_UNAVAILABLE", "x"));
        AppAngelOneMarketFeedService feed = new AppAngelOneMarketFeedService(ssm, sessions, quotes);

        assertThatThrownBy(() -> feed.quote(RELIANCE))
            .isInstanceOfSatisfying(MarketDataException.class,
                ex -> assertThat(ex.code()).isEqualTo("OWNER_SSM_ACCESS_UNAVAILABLE"));
        // A second caller inside the backoff window must not receive a generic code.
        assertThatThrownBy(() -> feed.quote(RELIANCE))
            .isInstanceOfSatisfying(MarketDataException.class,
                ex -> assertThat(ex.code()).isEqualTo("OWNER_SSM_ACCESS_UNAVAILABLE"));
    }
}
