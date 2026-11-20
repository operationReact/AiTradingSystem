package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class OwnerPrivateIntradayControllerTest {
    private final InstrumentMasterService instruments = mock(InstrumentMasterService.class);
    private final OwnerPrivateIntradayRepository repository = mock(OwnerPrivateIntradayRepository.class);

    private JwtAuthenticationToken jwt(String subject) {
        return new JwtAuthenticationToken(
            Jwt.withTokenValue("owner-test").header("alg","RS256").subject(subject).build(),
            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    private OwnerPrivateIntradayController controller() {
        return new OwnerPrivateIntradayController("owner-only",instruments,repository);
    }

    private void seedInstrument() {
        when(instruments.requireActiveInstrument(42L)).thenReturn(new Instrument(
            42L,"NSE_CM","NSE","2885","RELIANCE-EQ","Reliance",
            "EQ",null,null,1,new BigDecimal("0.05"),UUID.randomUUID()));
    }

    @Test void otherCustomersCannotAccessOrProbeOwnerIntraday() {
        assertThatThrownBy(()->controller().candles(jwt("another-customer"),42L,
            "FIVE_MINUTE",LocalDate.of(2026,11,12),LocalDate.of(2026,11,19)))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(ex->assertThat(((ResponseStatusException)ex).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(instruments,repository);
    }

    @Test void privateSourceNeverUsesSharedCandleRepository() {
        seedInstrument();
        var response=controller().candles(jwt("owner-only"),42L,
            "FIVE_MINUTE",LocalDate.of(2026,11,12),LocalDate.of(2026,11,19));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().interval()).isEqualTo("FIVE_MINUTE");
        verify(repository).candles(eq("owner-only"),eq("2885"),eq("FIVE_MINUTE"),
            any(),any());
    }

    @Test void invalidOrOversizedQueriesFailBeforeArchiveRead() {
        assertThatThrownBy(()->controller().candles(jwt("owner-only"),42L,
            "ONE_DAY",LocalDate.of(2026,11,12),LocalDate.of(2026,11,19)))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller().candles(jwt("owner-only"),42L,
            "ONE_HOUR",LocalDate.of(2026,8,1),LocalDate.of(2026,11,19)))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }
}
