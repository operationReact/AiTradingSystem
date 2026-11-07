package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.instrument.Instrument;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AngelOneLimitOrderPayloadTest {
    private final AngelOneLimitOrderPayload mapper =
        new AngelOneLimitOrderPayload(new ObjectMapper());

    private Instrument equity() {
        return new Instrument(
            1L, "NSE", "NSE", "3045", "SBIN-EQ", "State Bank of India",
            "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());
    }

    @Test
    void mapsApprovedEquityLimitDayWithNoDefaultSurveillanceConsent() {
        var request = mapper.build(new AngelOneLimitOrderPayload.ApprovedParameters(
            equity(), "BUY", 10, new BigDecimal("194.50"), "DELIVERY",
            "room-123", false));
        assertThat(request.path("variety").asText()).isEqualTo("NORMAL");
        assertThat(request.path("ordertype").asText()).isEqualTo("LIMIT");
        assertThat(request.path("duration").asText()).isEqualTo("DAY");
        assertThat(request.path("exchange").asText()).isEqualTo("NSE");
        assertThat(request.path("tradingsymbol").asText()).isEqualTo("SBIN-EQ");
        assertThat(request.path("symboltoken").asText()).isEqualTo("3045");
        assertThat(request.path("price").asText()).isEqualTo("194.50");
        assertThat(request.path("quantity").asText()).isEqualTo("10");
        assertThat(request.has("scripconsent")).isFalse();
        assertThat(request.has("apiKey")).isFalse();
        assertThat(request.has("jwtToken")).isFalse();
    }

    @Test
    void rejectsOffTickOrderAndDerivativeInstrument() {
        assertThatThrownBy(() -> mapper.build(
            new AngelOneLimitOrderPayload.ApprovedParameters(
                equity(), "BUY", 10, new BigDecimal("194.53"), "INTRADAY", "id1", false)))
            .isInstanceOf(IllegalArgumentException.class);
        var derivative = new Instrument(
            12L, "NFO", "NFO", "123", "SBIN-NOV-FUT", "SBIN",
            "FUT", null, null, 25, new BigDecimal("0.05"), UUID.randomUUID());
        assertThatThrownBy(() -> mapper.build(
            new AngelOneLimitOrderPayload.ApprovedParameters(
                derivative, "BUY", 10, new BigDecimal("194.50"), "INTRADAY", "id2", false)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderIsAlwaysLimitDayAndSurveillanceConsentMustBeExplicit() {
        var request = mapper.build(new AngelOneLimitOrderPayload.ApprovedParameters(
            equity(), "SELL", 1, new BigDecimal("195.00"), "INTRADAY", "id3", true));
        assertThat(request.path("ordertype").asText()).isEqualTo("LIMIT");
        assertThat(request.path("duration").asText()).isEqualTo("DAY");
        assertThat(request.path("scripconsent").asText()).isEqualTo("yes");
        assertThatThrownBy(() -> mapper.build(
            new AngelOneLimitOrderPayload.ApprovedParameters(
                equity(), "BUY", 0, new BigDecimal("195.00"), "INTRADAY", "id4", false)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
