package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.security.AccountApiOnlyFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;

class UpstoxFnoPaperPreflightControllerTest {
    private static final Instant MONDAY = Instant.parse("2026-11-16T04:01:00Z");
    private final UpstoxAnalyticsSource source = mock(UpstoxAnalyticsSource.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<UpstoxFnoV3LiveFeedService> provider =
            mock(ObjectProvider.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);

    private UpstoxFnoPaperPreflightController controller(MockEnvironment env, Instant time) {
        return new UpstoxFnoPaperPreflightController(source, provider, calendar, env,
                Clock.fixed(time, ZoneOffset.UTC));
    }

    private static MockEnvironment enabledFlags() {
        return new MockEnvironment()
                .withProperty("minttick.upstox-fno.v3-feed-enabled", "true")
                .withProperty("minttick.upstox-fno.paper-enabled", "true")
                .withProperty("minttick.upstox-fno.display-rights-verified", "true")
                .withProperty("minttick.upstox-fno.paper-usage-rights-verified", "true");
    }

    @Test
    void disabledByDefaultReturnsExplicitBlockersWithoutCreatingWebsocket() {
        var response = controller(new MockEnvironment(), MONDAY).preflight();
        var report = response.getBody();
        assertThat(report).isNotNull();
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(report.technicalStatus()).isEqualTo("TECHNICAL_PREFLIGHT_BLOCKED");
        assertThat(report.tradingApproved()).isFalse();
        assertThat(report.technicalBlockers()).contains(
                "ANALYTICS_SOURCE_DISABLED", "V3_FEED_FEATURE_DISABLED",
                "PAPER_FEATURE_DISABLED", "CUSTOMER_DISPLAY_FLAG_NOT_CONFIGURED",
                "PAPER_USAGE_FLAG_NOT_CONFIGURED", "PAPER_SESSION_CLOSED",
                "V3_STREAM_NOT_CONNECTED", "NO_FRESH_EXCHANGE_TIMED_QUOTES");
        assertThat(report.freshExchangeTimedQuotes()).isZero();
        assertThat(report.latestFreshExchangeTradeAt()).isNull();
        verifyNoInteractions(provider);
    }

    @Test
    void staleHistoricalTradeTimestampCanNeverPassPreflight() {
        when(source.enabled()).thenReturn(true);
        when(calendar.isInsideCashSession(MONDAY)).thenReturn(true);
        var feed = mock(UpstoxFnoV3LiveFeedService.class);
        when(provider.getIfAvailable()).thenReturn(feed);
        when(feed.status()).thenReturn(new UpstoxFnoV3LiveFeedService.Status(
                "CONNECTED", 2, 1, 6, 0, MONDAY, MONDAY.minusSeconds(86400),
                0, true, false, false, false, 0, null));

        var report = controller(enabledFlags(), MONDAY).preflight().getBody();
        assertThat(report).isNotNull();
        assertThat(report.technicalStatus()).isEqualTo("TECHNICAL_PREFLIGHT_BLOCKED");
        assertThat(report.technicalBlockers()).containsExactly("NO_FRESH_EXCHANGE_TIMED_QUOTES");
        assertThat(report.tradingApproved()).isFalse();
        assertThat(report.independentProviderRightsEvidence()).isEqualTo("NOT_CHECKED");
        verifyNoInteractionsWithSourceBeyondEnabled();
    }

    @Test
    void freshSyntheticFeedPassesOnlyTechnicalPreflightNeverTradingApproval() {
        when(source.enabled()).thenReturn(true);
        when(calendar.isInsideCashSession(MONDAY)).thenReturn(true);
        var feed = mock(UpstoxFnoV3LiveFeedService.class);
        when(provider.getIfAvailable()).thenReturn(feed);
        when(feed.status()).thenReturn(new UpstoxFnoV3LiveFeedService.Status(
                "CONNECTED", 2, 2, 4, 0, MONDAY, MONDAY.minusSeconds(2),
                0, true, false, false, false, 2, MONDAY.minusSeconds(2)));

        var response = controller(enabledFlags(), MONDAY).preflight();
        var report = response.getBody();
        assertThat(report).isNotNull();
        assertThat(report.technicalStatus()).isEqualTo("TECHNICAL_PREFLIGHT_PASSED");
        assertThat(report.technicalBlockers()).isEmpty();
        assertThat(report.tradingApproved()).isFalse();
        assertThat(report.independentProviderRightsEvidence()).isEqualTo("NOT_CHECKED");
        assertThat(report.authenticatedPaperBuyCloseEvidence()).isEqualTo("NOT_CHECKED");
        assertThat(report.deviceAcceptanceEvidence()).isEqualTo("NOT_CHECKED");
        assertThat(report.freshExchangeTimedQuotes()).isEqualTo(2);
        assertThat(report.latestFreshExchangeTradeAt()).isEqualTo(MONDAY.minusSeconds(2));
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        verifyNoInteractionsWithSourceBeyondEnabled();
    }

    @Test
    void privateOperatorPreflightIsDeniedByPublicAccountFilterAndRoleProtected()
            throws Exception {
        var request = new MockHttpServletRequest("GET",
                "/api/admin/market-data/upstox-analytics/fno/paper/preflight");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new AccountApiOnlyFilter().doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.getRequest()).isNull();

        var method = UpstoxFnoPaperPreflightController.class.getMethod("preflight");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("ADMIN", "OPERATOR");
    }

    private void verifyNoInteractionsWithSourceBeyondEnabled() {
        verify(source).enabled();
        verifyNoMoreInteractions(source);
    }
}
