package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import in.aitradingsystem.security.AccountApiOnlyFilter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

class UpstoxFnoReadinessProbeControllerTest {
    private static final String UNDERLYING = "NSE_INDEX|Nifty 50";
    private static final LocalDate EXPIRY = LocalDate.parse("2026-11-19");
    private static final Instant NOW = Instant.parse("2026-11-14T08:00:00Z");
    private final UpstoxAnalyticsSource source = mock(UpstoxAnalyticsSource.class);

    private UpstoxFnoReadinessProbeController controller() {
        return new UpstoxFnoReadinessProbeController(
                source, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static UpstoxAnalyticsSource.FnoOptionContract contract(String key, String side) {
        return new UpstoxAnalyticsSource.FnoOptionContract(
                UNDERLYING, key, EXPIRY, new BigDecimal("25000"), side, 65,
                new BigDecimal("0.05"), "NIFTY " + side);
    }

    private static UpstoxAnalyticsSource.FnoOptionQuote quote(String key, boolean greeks) {
        return new UpstoxAnalyticsSource.FnoOptionQuote(
                key, new BigDecimal("120"), new BigDecimal("119"),
                new BigDecimal("121"), 200L, 4000L, 3800L,
                greeks ? new BigDecimal("19.3") : null,
                greeks ? new BigDecimal("0.5") : null,
                greeks ? new BigDecimal("0.01") : null,
                greeks ? new BigDecimal("-2.1") : null,
                greeks ? new BigDecimal("4.1") : null);
    }

    private static UpstoxAnalyticsSource.OptionChainSnapshot chain(
            String callKey, String putKey, boolean hasGreeks) {
        return new UpstoxAnalyticsSource.OptionChainSnapshot(
                "UPSTOX_ANALYTICS", NOW, false,
                List.of(new UpstoxAnalyticsSource.FnoOptionStrike(
                        EXPIRY, UNDERLYING, new BigDecimal("25000"),
                        new BigDecimal("25010"), quote(callKey, hasGreeks),
                        quote(putKey, hasGreeks))));
    }

    @Test
    void readsTwoBoundedSourceMethodsAndReturnsOnlySafeCounters() {
        when(source.enabled()).thenReturn(true);
        when(source.currentFnoOptionContracts(UNDERLYING, EXPIRY))
                .thenReturn(List.of(contract("NSE_FO|61001", "CE"),
                        contract("NSE_FO|61002", "PE")));
        when(source.currentFnoOptionChain(UNDERLYING, EXPIRY))
                .thenReturn(chain("NSE_FO|61001", "NSE_FO|61002", true));

        var response = controller().probe(EXPIRY);
        var result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("READS_VERIFIED");
        assertThat(result.validatedContracts()).isEqualTo(2);
        assertThat(result.validatedStrikes()).isEqualTo(1);
        assertThat(result.callsWithContract()).isEqualTo(1);
        assertThat(result.putsWithContract()).isEqualTo(1);
        assertThat(result.rowsWithCompleteGreeks()).isEqualTo(1);
        assertThat(result.exchangeQuoteTimeVerified()).isFalse();
        assertThat(result.stored()).isFalse();
        assertThat(result.displayRightsVerified()).isFalse();
        assertThat(result.modelTrainingRightsVerified()).isFalse();
        assertThat(result.liveTradingEnabled()).isFalse();
        assertThat(result.observedAt()).isEqualTo(NOW);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        verify(source).currentFnoOptionContracts(UNDERLYING, EXPIRY);
        verify(source).currentFnoOptionChain(UNDERLYING, EXPIRY);
        verify(source).enabled();
        verifyNoMoreInteractions(source);
    }

    @Test
    void incompleteContractIdentityIsNotCountedAsVerified() {
        when(source.enabled()).thenReturn(true);
        when(source.currentFnoOptionContracts(UNDERLYING, EXPIRY))
                .thenReturn(List.of(contract("NSE_FO|61001", "CE")));
        when(source.currentFnoOptionChain(UNDERLYING, EXPIRY))
                .thenReturn(chain("NSE_FO|61001", "NSE_FO|61002", false));

        var result = controller().probe(EXPIRY).getBody();
        assertThat(result.status()).isEqualTo("PARTIAL_DATA");
        assertThat(result.validatedContracts()).isEqualTo(1);
        assertThat(result.callsWithContract()).isEqualTo(1);
        assertThat(result.putsWithContract()).isZero();
        assertThat(result.rowsWithCompleteGreeks()).isZero();
    }

    @Test
    void zeroProviderObservationsNeverBecomeVerifiedData() {
        when(source.enabled()).thenReturn(true);
        when(source.currentFnoOptionContracts(UNDERLYING, EXPIRY))
                .thenReturn(List.of());
        when(source.currentFnoOptionChain(UNDERLYING, EXPIRY))
                .thenReturn(new UpstoxAnalyticsSource.OptionChainSnapshot(
                        "UPSTOX_ANALYTICS", NOW, false, List.of()));
        var result = controller().probe(EXPIRY).getBody();
        assertThat(result.status()).isEqualTo("PARTIAL_DATA");
        assertThat(result.validatedStrikes()).isZero();
    }

    @Test
    void invalidOrExpiredDateCannotTriggerProviderOrSsm() {
        var probe = controller();
        assertThat(probe.probe(null).getBody().status()).isEqualTo("INVALID_EXPIRY");
        assertThat(probe.probe(LocalDate.parse("2026-11-12")).getBody().status())
                .isEqualTo("INVALID_EXPIRY");
        assertThat(probe.probe(LocalDate.parse("2027-08-20")).getBody().status())
                .isEqualTo("INVALID_EXPIRY");
        verifyNoInteractions(source);
    }

    @Test
    void disabledProbeReturnsNoDataWithoutReadingProvider() {
        when(source.enabled()).thenReturn(false);
        var result = controller().probe(EXPIRY).getBody();
        assertThat(result.status()).isEqualTo("DISABLED");
        assertThat(result.validatedContracts()).isZero();
        verify(source).enabled();
        verifyNoMoreInteractions(source);
    }

    @Test
    void fifteenMinuteCooldownPreventsRepeatedUpstreamCalls() {
        when(source.enabled()).thenReturn(true);
        when(source.currentFnoOptionContracts(UNDERLYING, EXPIRY))
                .thenReturn(List.of());
        when(source.currentFnoOptionChain(UNDERLYING, EXPIRY))
                .thenReturn(new UpstoxAnalyticsSource.OptionChainSnapshot(
                        "UPSTOX_ANALYTICS", NOW, false, List.of()));
        var probe = controller();
        assertThat(probe.probe(EXPIRY).getBody().status()).isEqualTo("PARTIAL_DATA");
        assertThat(probe.probe(EXPIRY).getBody().status()).isEqualTo("COOLDOWN");
        verify(source, times(1)).currentFnoOptionContracts(UNDERLYING, EXPIRY);
        verify(source, times(1)).currentFnoOptionChain(UNDERLYING, EXPIRY);
    }

    @Test
    void providerFailureIsSanitizedAndStillConsumesBudget() {
        when(source.enabled()).thenReturn(true);
        when(source.currentFnoOptionContracts(UNDERLYING, EXPIRY))
                .thenThrow(new IllegalStateException("secret HTTP body bearer header"));
        var probe = controller();
        var response = probe.probe(EXPIRY);
        assertThat(response.getBody().status()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(response.getBody().toString()).doesNotContain("secret", "bearer");
        assertThat(probe.probe(EXPIRY).getBody().status()).isEqualTo("COOLDOWN");
        verify(source, never()).currentFnoOptionChain(UNDERLYING, EXPIRY);
    }

    @Test
    void falseSourceProvenanceIsNotRepresentedAsVerified() {
        when(source.enabled()).thenReturn(true);
        when(source.currentFnoOptionContracts(UNDERLYING, EXPIRY))
                .thenReturn(List.of(contract("NSE_FO|61001", "CE")));
        when(source.currentFnoOptionChain(UNDERLYING, EXPIRY))
                .thenReturn(new UpstoxAnalyticsSource.OptionChainSnapshot(
                        "UPSTOX_ANALYTICS", NOW, true, List.of()));
        assertThat(controller().probe(EXPIRY).getBody().status())
                .isEqualTo("INVALID_PROVENANCE");
    }

    @Test
    void endpointIsPostOnlyPreauthorizedAndFeatureGated() throws Exception {
        assertThat(UpstoxFnoReadinessProbeController.class
                .isAnnotationPresent(ConditionalOnProperty.class)).isTrue();
        var method = UpstoxFnoReadinessProbeController.class
                .getMethod("probe", LocalDate.class);
        assertThat(method.isAnnotationPresent(PostMapping.class)).isTrue();
        var role = method.getAnnotation(PreAuthorize.class);
        assertThat(role).isNotNull();
        assertThat(role.value()).contains("ADMIN", "OPERATOR");
    }
    @Test
    void customerAccountIsolationFilterRejectsPrivateFnoProbe() throws Exception {
        var request = new MockHttpServletRequest("POST",
                "/api/admin/market-data/upstox-analytics/fno/probe");
        var response = new MockHttpServletResponse();
        var filter = new AccountApiOnlyFilter();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.getRequest()).isNull();
        verifyNoInteractions(source);
    }

}
