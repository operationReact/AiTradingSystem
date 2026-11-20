package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class OwnerHistoryChartControllerTest {
    @TempDir Path tmp;
    private final InstrumentMasterService instruments = mock(InstrumentMasterService.class);

    private JwtAuthenticationToken jwt(String sub) {
        var jwt = Jwt.withTokenValue("owner-token")
            .header("alg", "RS256")
            .subject(sub).build();
        return new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    private OwnerHistoryChartController controller() {
        return new OwnerHistoryChartController("private-owner", tmp.toString(),
            tmp.resolve("monthly").toString(), new ObjectMapper(), instruments);
    }

    private void instrument() {
        when(instruments.requireActiveInstrument(42L)).thenReturn(new Instrument(
            42L, "NSE_CM", "NSE", "2885", "RELIANCE-EQ", "Reliance",
            "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID()));
    }

    private void chart(String token, String symbol) throws Exception {
        String json = "{\"schema\":\"minttick.owner.chart.v1\",\"generatedAt\":\""
            + OffsetDateTime.now(ZoneOffset.UTC)
            + "\",\"exchange\":\"NSE\",\"interval\":\"ONE_DAY\",\"token\":\""
            + token + "\",\"symbol\":\"" + symbol + "\",\"rows\":["
            + "[\"2026-11-09T15:30:00+05:30\",100,110,90,105,10000],"
            + "[\"2026-11-10T15:30:00+05:30\",106,112,104,108,12000]]}";
        Files.writeString(tmp.resolve("2885.json"), json);
    }

    @Test void ownerGetsTwoRealStoredDailyBarsWithoutBrokerToken() throws Exception {
        instrument();
        chart("2885", "RELIANCE-EQ");
        var result = controller().candles(jwt("private-owner"), 42L,
            LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 18)).getBody();
        assertThat(result).isNotNull();
        assertThat(result.instrumentId()).isEqualTo(42);
        assertThat(result.interval()).isEqualTo("ONE_DAY");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(1).close()).isEqualByComparingTo("108");
        assertThat(result.items().get(1).source()).isEqualTo("ANGEL_ONE_OWNER_PRIVATE_ARCHIVE");
        assertThat(result.hasNext()).isFalse();
        verify(instruments).requireActiveInstrument(42);
    }

    @Test void ownerGetsOnlyRealAggregatedMonthlyBarsOverFiveYears() throws Exception {
        instrument();
        Path monthDir = Files.createDirectory(tmp.resolve("monthly"));
        String json = "{\"schema\":\"minttick.owner.chart.monthly.v1\",\"generatedAt\":\""
            + OffsetDateTime.now(ZoneOffset.UTC)
            + "\",\"exchange\":\"NSE\",\"interval\":\"ONE_MONTH\","
            + "\"sourceInterval\":\"ONE_DAY\","
            + "\"aggregation\":\"FIRST_OPEN_MAX_HIGH_MIN_LOW_LAST_CLOSE_SUM_VOLUME\","
            + "\"sessionCompleteness\":\"UNVERIFIED\","
            + "\"token\":\"2885\",\"symbol\":\"RELIANCE-EQ\",\"rows\":["
            + "[\"2020-01-02T15:30:00+05:30\",100,130,90,120,20000],"
            + "[\"2026-11-02T15:30:00+05:30\",200,230,190,220,30000]]}";
        Files.writeString(monthDir.resolve("2885.json"), json);
        var result = controller().monthlyCandles(jwt("private-owner"), 42L,
            LocalDate.of(2020, 1, 1), LocalDate.of(2026, 11, 20)).getBody();
        assertThat(result).isNotNull();
        assertThat(result.interval()).isEqualTo("ONE_MONTH");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).high()).isEqualByComparingTo("130");
        assertThat(result.items().get(1).volume()).isEqualTo(30000L);
        assertThat(result.items().get(1).source())
            .isEqualTo("ANGEL_ONE_OWNER_PRIVATE_ARCHIVE_MONTHLY_FROM_DAILY");
        assertThat(result.hasNext()).isFalse();
        assertThatThrownBy(() -> controller().monthlyCandles(jwt("not-owner"), 42L,
            LocalDate.of(2020, 1, 1), LocalDate.of(2026, 11, 20)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> controller().candles(jwt("private-owner"), 42L,
            LocalDate.of(2020, 1, 1), LocalDate.of(2026, 11, 20)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test void anotherCustomerCannotReadOrProbeOwnerChart() throws Exception {
        instrument();
        chart("2885", "RELIANCE-EQ");
        assertThatThrownBy(() -> controller().candles(jwt("another-account"), 42L,
            LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 18)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(instruments);
    }

    @Test void rejectsTokenMismatchAndSymlinkWithoutServingCandles() throws Exception {
        instrument();
        chart("9999", "RELIANCE-EQ");
        assertThatThrownBy(() -> controller().candles(jwt("private-owner"), 42L,
            LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 18)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        Files.delete(tmp.resolve("2885.json"));
        Files.writeString(tmp.resolve("actual.json"), "{}");
        Files.createSymbolicLink(tmp.resolve("2885.json"), tmp.resolve("actual.json"));
        assertThatThrownBy(() -> controller().candles(jwt("private-owner"), 42L,
            LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 18)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
