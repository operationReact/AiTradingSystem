package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AngelOneIntradayEquityOrderPayloadTest {
    private static AngelOneIntradayEquityOrderPayload.VerifiedOrder order(String exchange,
            String token, String side, int qty, String price, String tag) {
        return new AngelOneIntradayEquityOrderPayload.VerifiedOrder(exchange, token,
            "SBIN-EQ", side, qty, new BigDecimal(price), tag);
    }

    @Test
    void buildsOnlyIntradayLimitDayCashEquityFields() {
        var payload = AngelOneIntradayEquityOrderPayload.build(
            order("NSE", "3045", "BUY", 10, "550.25", "MTabcdefgh12"));
        assertThat(payload.path("variety").asText()).isEqualTo("NORMAL");
        assertThat(payload.path("ordertype").asText()).isEqualTo("LIMIT");
        assertThat(payload.path("producttype").asText()).isEqualTo("INTRADAY");
        assertThat(payload.path("duration").asText()).isEqualTo("DAY");
        assertThat(payload.path("exchange").asText()).isEqualTo("NSE");
        assertThat(payload.path("quantity").asText()).isEqualTo("10");
        assertThat(payload.path("price").asText()).isEqualTo("550.25");
        assertThat(payload.path("ordertag").asText()).isEqualTo("MTabcdefgh12");
        assertThat(payload.has("disclosedquantity")).isFalse();
    }

    @Test
    void rejectsAnythingOutsideTheSupportedShape() {
        assertThatThrownBy(() -> AngelOneIntradayEquityOrderPayload.build(
                order("MCX", "3045", "BUY", 1, "10", "MTabcdefgh12")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AngelOneIntradayEquityOrderPayload.build(
                order("NSE", "abc", "BUY", 1, "10", "MTabcdefgh12")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AngelOneIntradayEquityOrderPayload.build(
                order("NSE", "3045", "BUY", 0, "10", "MTabcdefgh12")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AngelOneIntradayEquityOrderPayload.build(
                order("NSE", "3045", "BUY", 10001, "10", "MTabcdefgh12")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AngelOneIntradayEquityOrderPayload.build(
                order("NSE", "3045", "BUY", 1, "10.001", "MTabcdefgh12")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AngelOneIntradayEquityOrderPayload.build(
                order("NSE", "3045", "BUY", 1, "10", "short")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AngelOneIntradayEquityOrderPayload.build(
                order("NSE", "3045", "HOLD", 1, "10", "MTabcdefgh12")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
