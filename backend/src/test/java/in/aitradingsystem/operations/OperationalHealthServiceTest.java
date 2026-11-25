package in.aitradingsystem.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.marketdata.IndianCashMarketCalendar;
import in.aitradingsystem.provider.angelone.AngelOneHealthService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

class OperationalHealthServiceTest {
    @Test
    void databaseFailureReturnsUnknownControlsWithoutLeakingSecretsOrCallingBroker() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        AngelOneHealthService provider = mock(AngelOneHealthService.class);
        Instant now = Instant.parse("2026-10-30T04:30:00Z");
        when(provider.status()).thenReturn(new AngelOneHealthService.ProviderHealth(
            AngelOneHealthService.State.UNAVAILABLE,"super-secret-provider-payload",null,null,now,false,null));
        when(jdbc.sql("SELECT 1")).thenThrow(new DataAccessResourceFailureException("jdbc:password=super-secret"));
        var service = new OperationalHealthService(jdbc,provider,mock(IndianCashMarketCalendar.class),
            Clock.fixed(now,ZoneOffset.UTC),86400,300,3600,3600);
        var result = service.snapshot();
        assertThat(result.state()).isEqualTo(OperationalHealthService.State.UNAVAILABLE);
        assertThat(result.executionMode()).isEqualTo("UNKNOWN");
        assertThat(result.emergencyStopActive()).isNull();
        assertThat(result.liveAvailable()).isFalse();
        String output = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(output).doesNotContain("super-secret","password","jdbc:");
        verify(provider).status();
        verifyNoMoreInteractions(provider);
    }
}
