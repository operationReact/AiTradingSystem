package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.security.AccountApiOnlyFilter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;

class UpstoxFnoV3LiveOperatorControllerTest {
    private static final String UNDERLYING = "NSE_INDEX|Nifty 50";
    private static final LocalDate EXPIRY = LocalDate.parse("2026-11-19");
    private final UpstoxAnalyticsSource source = mock(UpstoxAnalyticsSource.class);
    private final UpstoxFnoV3LiveFeedService service = mock(UpstoxFnoV3LiveFeedService.class);

    private UpstoxFnoV3LiveOperatorController controller() {
        return new UpstoxFnoV3LiveOperatorController(source, service,
                Clock.fixed(Instant.parse("2026-11-14T09:00:00Z"), ZoneOffset.UTC));
    }

    private static UpstoxAnalyticsSource.FnoOptionContract contract(String key,
            String side, String strike) {
        return new UpstoxAnalyticsSource.FnoOptionContract(
                UNDERLYING, key, EXPIRY, new BigDecimal(strike), side, 65,
                new BigDecimal(".05"), "NIFTY " + side);
    }

    private static UpstoxAnalyticsSource.FnoOptionQuote quote(String key) {
        return new UpstoxAnalyticsSource.FnoOptionQuote(key, new BigDecimal("20"),
                new BigDecimal("19"), new BigDecimal("21"), 1, 1, 1,
                null, null, null, null, null);
    }

    private static UpstoxAnalyticsSource.FnoOptionStrike strike(String at, String ce, String pe) {
        return new UpstoxAnalyticsSource.FnoOptionStrike(EXPIRY, UNDERLYING,
                new BigDecimal(at), new BigDecimal("25010"), quote(ce), quote(pe));
    }

    @Test
    void startsOnlyProviderVerifiedPairedCePeContractsNearSpot() {
        when(source.enabled()).thenReturn(true);
        when(source.currentFnoOptionContracts(UNDERLYING, EXPIRY)).thenReturn(List.of(
                contract("NSE_FO|101", "CE", "25000"),
                contract("NSE_FO|102", "PE", "25000"),
                contract("NSE_FO|103", "CE", "27000")));
        when(source.currentFnoOptionChain(UNDERLYING, EXPIRY))
                .thenReturn(new UpstoxAnalyticsSource.OptionChainSnapshot(
                        "UPSTOX_ANALYTICS", Instant.now(), false, List.of(
                                strike("27000", "NSE_FO|103", "NSE_FO|104"),
                                strike("25000", "NSE_FO|101", "NSE_FO|102"))));
        when(service.start(anyList())).thenReturn(
                new UpstoxFnoV3LiveFeedService.Status("CONNECTING", 2, 0, 0, 0,
                        null, null, 0, false, false, false, false, 0, null));

        var response = controller().start(EXPIRY);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        verify(service).start(List.of("NSE_FO|101", "NSE_FO|102"));
        verifyNoMoreInteractions(service);
    }

    @Test
    void expiryValidationHappensBeforeAnyProviderAccess() {
        assertThatThrownBy(() -> controller().start(LocalDate.parse("2025-11-19")))
                .hasMessage("UPSTOX_FNO_INVALID_EXPIRY");
        assertThatThrownBy(() -> controller().start(LocalDate.parse("2027-12-19")))
                .hasMessage("UPSTOX_FNO_INVALID_EXPIRY");
        verifyNoInteractions(source, service);
    }

    @Test
    void cannotStartWithoutMatchingProviderContracts() {
        when(source.enabled()).thenReturn(true);
        when(source.currentFnoOptionContracts(UNDERLYING, EXPIRY))
                .thenReturn(List.of(contract("NSE_FO|101", "CE", "25000")));
        when(source.currentFnoOptionChain(UNDERLYING, EXPIRY))
                .thenReturn(new UpstoxAnalyticsSource.OptionChainSnapshot(
                        "UPSTOX_ANALYTICS", Instant.now(), false,
                        List.of(strike("25000", "NSE_FO|101", "NSE_FO|102"))));
        assertThatThrownBy(() -> controller().start(EXPIRY))
                .hasMessage("UPSTOX_FNO_NO_MATCHING_CONTRACTS");
        verifyNoInteractions(service);
    }

    @Test
    void privateAdminRoutesAreDeniedAtTheAccountPublicFilter() throws Exception {
        for (String method : List.of("POST", "GET")) {
            var req = new MockHttpServletRequest(method,
                    "/api/admin/market-data/upstox-analytics/fno/live/start");
            var resp = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            new AccountApiOnlyFilter().doFilter(req, resp, chain);
            assertThat(resp.getStatus()).isEqualTo(404);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void adminMethodsRequireRoleAndExplicitFeatureFlag() throws Exception {
        assertThat(UpstoxFnoV3LiveOperatorController.class
                .getAnnotation(ConditionalOnProperty.class).havingValue()).isEqualTo("true");
        for (String name : List.of("start", "status", "stop")) {
            var m = name.equals("start")
                    ? UpstoxFnoV3LiveOperatorController.class.getMethod(name, LocalDate.class)
                    : UpstoxFnoV3LiveOperatorController.class.getMethod(name);
            assertThat(m.getAnnotation(PreAuthorize.class).value())
                    .contains("ADMIN", "OPERATOR");
        }
    }
}
