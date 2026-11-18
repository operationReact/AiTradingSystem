package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.marketdata.MarketDataException;
import org.junit.jupiter.api.Test;

/** The application feed keeps a stable, secret-free reason code for operators. */
class AppAngelOneReasonCodeTest {

    @Test
    void ssmAndTotpCodesArePreserved() {
        assertThat(AppAngelOneMarketFeedService.reasonCode(
                new AngelOneException(AngelOneException.Kind.NOT_CONFIGURED, "OWNER_SSM_ACCESS_UNAVAILABLE",
                        "Owner's secure Angel One connection is unavailable")))
            .isEqualTo("OWNER_SSM_ACCESS_UNAVAILABLE");
        assertThat(AppAngelOneMarketFeedService.reasonCode(
                new AngelOneException(AngelOneException.Kind.NOT_CONFIGURED, "OWNER_SSM_TOTP_INVALID", "x")))
            .isEqualTo("OWNER_SSM_TOTP_INVALID");
    }

    @Test
    void marketDataCodesArePreserved() {
        assertThat(AppAngelOneMarketFeedService.reasonCode(new MarketDataException("PROVIDER_NOT_CONFIGURED", "x")))
            .isEqualTo("PROVIDER_NOT_CONFIGURED");
    }

    @Test
    void unknownOrUnsafeCodesFallBackToTheGenericCode() {
        assertThat(AppAngelOneMarketFeedService.reasonCode(new IllegalStateException("token=abc123")))
            .isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(AppAngelOneMarketFeedService.reasonCode(
                new AngelOneException(AngelOneException.Kind.NOT_CONFIGURED, "bad code with spaces", "x")))
            .isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(AppAngelOneMarketFeedService.reasonCode(
                new AngelOneException(AngelOneException.Kind.NOT_CONFIGURED, "lowercase", "x")))
            .isEqualTo("PROVIDER_UNAVAILABLE");
    }
}
