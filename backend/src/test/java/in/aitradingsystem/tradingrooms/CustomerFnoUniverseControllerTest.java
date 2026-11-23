package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.provider.upstox.UpstoxFnoInstrumentUniverse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class CustomerFnoUniverseControllerTest {
    private static JwtAuthenticationToken customer() {
        var token = mock(JwtAuthenticationToken.class);
        when(token.getAuthorities()).thenReturn(
            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        return token;
    }

    @Test
    void unauthorizedClientCannotReadMasterOrItsCoverage() {
        var registry = mock(FnoFuturesContractRegistry.class);
        var controller = new CustomerFnoUniverseController(registry);
        assertThatThrownBy(() -> controller.status(null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> controller.contracts(null,
            null, null, null, 0, 50))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(registry);
    }

    @Test
    void ownerGetsPaginatedListedContractWithExplicitNonLiveProvenance() {
        var registry = mock(FnoFuturesContractRegistry.class);
        var status = new FnoFuturesContractRegistry.UniverseStatus(
            "LOADED", null, Instant.parse("2026-11-23T03:00:00Z"),
            "official-master-sha", 2, 0, 1, 1, 1);
        var call = new UpstoxFnoInstrumentUniverse.Contract(
            "NSE_FO|101", "NSE_INDEX|Nifty 50", "NIFTY",
            "NIFTY 26000 CE", "CE", LocalDate.of(2026, 12, 1),
            new BigDecimal("26000"), 65, new BigDecimal("5"),
            new BigDecimal("100000"), true);
        when(registry.universeStatus()).thenReturn(status);
        when(registry.universe("NSE_INDEX|Nifty 50",
            LocalDate.of(2026, 12, 1), "CE", 0, 50))
            .thenReturn(new FnoFuturesContractRegistry.UniverseResult(
                status, 1, List.of(call)));
        var c = new CustomerFnoUniverseController(registry);
        var result = c.contracts(customer(), "NSE_INDEX|Nifty 50",
            LocalDate.of(2026, 12, 1), "CE", 0, 50);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().provenance())
            .isEqualTo("UPSTOX_BOD_INSTRUMENT_MASTER");
        assertThat(result.getBody().hasRealtimeQuotes()).isFalse();
        assertThat(result.getBody().liveOrderAuthorized()).isFalse();
        assertThat(result.getBody().contracts()).containsExactly(call);
        assertThat(result.getBody().totalMatches()).isEqualTo(1);
        assertThat(result.getHeaders().getCacheControl()).contains("no-store");
        assertThat(c.status(customer()).getBody().sha256())
            .isEqualTo("official-master-sha");
    }

    @Test
    void oversizedOrUnverifiedUniverseQueryFailsBeforeRegistryAccess() {
        var registry = mock(FnoFuturesContractRegistry.class);
        var controller = new CustomerFnoUniverseController(registry);
        assertThatThrownBy(() -> controller.contracts(customer(),
            null, null, "ALL", 0, 50))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.contracts(customer(),
            null, null, null, -1, 50))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.contracts(customer(),
            null, null, null, 0, 101))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.contracts(customer(),
            "INVALID|underlying", null, "CE", 0, 50))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(registry);
    }
}
