package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;

import in.aitradingsystem.user.CustomerIdentityStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class CustomerPaperFnoCapabilityControllerTest {
    @Test
    void noFlagsReturnsHonestDisabledStatusNotA500OrEmptyTradingAccount() {
        var result = CustomerPaperFnoCapabilityController.assess(
            "INTRADAY", false, false, false, false);
        assertThat(result.status()).isEqualTo("FNO_PAPER_DISABLED");
        assertThat(result.blockerCodes()).containsExactly(
            "FNO_PAPER_DISABLED", "FNO_V3_FEED_DISABLED",
            "DISPLAY_RIGHTS_NOT_VERIFIED", "PAPER_USE_RIGHTS_NOT_VERIFIED");
        assertThat(result.readOnlyConfigured()).isFalse();
        assertThat(result.paperConfigured()).isFalse();
        assertThat(result.liveExecutionAuthorized()).isFalse();
    }

    @Test
    void missingRightsCannotEnableExpiryReadsOrPaperOrders() {
        var missingDisplay = CustomerPaperFnoCapabilityController.assess(
            "SWING", true, true, false, true);
        assertThat(missingDisplay.readOnlyConfigured()).isFalse();
        assertThat(missingDisplay.paperConfigured()).isFalse();
        assertThat(missingDisplay.status()).isEqualTo("DISPLAY_RIGHTS_NOT_VERIFIED");
        var missingPaper = CustomerPaperFnoCapabilityController.assess(
            "SWING", true, true, true, false);
        assertThat(missingPaper.readOnlyConfigured()).isTrue();
        assertThat(missingPaper.paperConfigured()).isFalse();
        assertThat(missingPaper.status()).isEqualTo("PAPER_USE_RIGHTS_NOT_VERIFIED");
    }

    @Test
    void realExchangeQuotesCanBeViewedWithoutEnablingVirtualOrders() {
        var result = CustomerPaperFnoCapabilityController.assess(
            "SWING", false, true, true, false);
        assertThat(result.readOnlyConfigured()).isTrue();
        assertThat(result.paperConfigured()).isFalse();
        assertThat(result.liveExecutionAuthorized()).isFalse();
        assertThat(result.blockerCodes()).containsExactly(
            "FNO_PAPER_DISABLED", "PAPER_USE_RIGHTS_NOT_VERIFIED");
        // Without separately verified display rights, even read-only live
        // NSE data must remain locked.
        assertThat(CustomerPaperFnoCapabilityController.assess(
            "SWING", false, true, false, false).readOnlyConfigured()).isFalse();
    }

    @Test
    void longTermAndUndefinedHorizonNeverAdmitOptionTrading() {
        for (var focus : new String[]{"LONG_TERM", "HELP_ME_CHOOSE", ""}) {
            var result = CustomerPaperFnoCapabilityController.assess(
                focus, true, true, true, true);
            assertThat(result.status()).isEqualTo("ROOM_HORIZON_UNSUPPORTED");
            assertThat(result.readOnlyConfigured()).isFalse();
            assertThat(result.paperConfigured()).isFalse();
        }
    }

    @Test
    void allFlagsOnlyPermitSourceChecksNotLiveOrUnverifiedOrderExecution() {
        var result = CustomerPaperFnoCapabilityController.assess(
            "INTRADAY", true, true, true, true);
        assertThat(result.status()).isEqualTo("SOURCE_CHECK_REQUIRED");
        assertThat(result.readOnlyConfigured()).isTrue();
        assertThat(result.paperConfigured()).isTrue();
        assertThat(result.liveExecutionAuthorized()).isFalse();
        assertThat(result.blockerCodes()).isEmpty();
    }

    @Test
    void noUnauthenticatedConfigLeakOrDatabaseRead() {
        var jdbc = mock(JdbcClient.class);
        var identities = mock(CustomerIdentityStore.class);
        var controller = new CustomerPaperFnoCapabilityController(
            jdbc, identities, mock(Environment.class));
        assertThatThrownBy(() -> controller.capability(
            java.util.UUID.randomUUID(), null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(jdbc, identities);
    }
}
