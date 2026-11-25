package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class UpstoxFnoLimitOrderPayloadTest {
    private final UpstoxFnoLimitOrderPayload builder =
        new UpstoxFnoLimitOrderPayload(new ObjectMapper());
    private static final LocalDate TODAY = LocalDate.parse("2026-11-16");
    private static final LocalDate EXPIRY = LocalDate.parse("2026-11-17");

    private UpstoxFnoLimitOrderPayload.VerifiedParameters candidate(
            String focus, String side, int lots, int held, String instrument) {
        return new UpstoxFnoLimitOrderPayload.VerifiedParameters(
            "NSE_INDEX|Nifty 50", instrument, "CE", TODAY, EXPIRY, 65,
            new BigDecimal("0.05"), lots, side, focus,
            new BigDecimal("100.05"), held, "MintTick_181");
    }

    @Test
    void intradayBuyUsesUnitsNotLotsAndNeverAmoOrAutoSlice() {
        var payload = builder.build(candidate("INTRADAY", "BUY", 2, 0, "NSE_FO|43919"));
        assertThat(payload.path("quantity").asInt()).isEqualTo(130);
        assertThat(payload.path("product").asText()).isEqualTo("I");
        assertThat(payload.path("instrument_token").asText()).isEqualTo("NSE_FO|43919");
        assertThat(payload.path("order_type").asText()).isEqualTo("LIMIT");
        assertThat(payload.path("validity").asText()).isEqualTo("DAY");
        assertThat(payload.path("transaction_type").asText()).isEqualTo("BUY");
        assertThat(payload.path("price").decimalValue()).isEqualByComparingTo("100.05");
        assertThat(payload.path("is_amo").asBoolean()).isFalse();
        assertThat(payload.path("slice").asBoolean()).isFalse();
        assertThat(payload.path("tag").asText()).isEqualTo("MintTick_181");
        assertThat(payload.has("access_token")).isFalse();
    }

    @Test
    void positionalCloseRequiresAtLeastExactOwnedLongUnits() {
        var payload = builder.build(candidate("SWING", "SELL", 1, 65, "NSE_FO|43919"));
        assertThat(payload.path("product").asText()).isEqualTo("D");
        assertThat(payload.path("quantity").asInt()).isEqualTo(65);
        assertThatThrownBy(() -> builder.build(
            candidate("SWING", "SELL", 2, 65, "NSE_FO|43919")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Uncovered");
        assertThatThrownBy(() -> builder.build(
            candidate("SWING", "SELL", 1, 0, "NSE_FO|43919")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Uncovered");
    }

    @Test
    void rejectsOutsideNiftyOrIncorrectExpiryAndNonOptionHorizon() {
        var good = candidate("INTRADAY", "BUY", 1, 0, "NSE_FO|43919");
        assertThatThrownBy(() -> builder.build(new UpstoxFnoLimitOrderPayload.VerifiedParameters(
            "NSE_INDEX|Bank Nifty", good.instrumentKey(), "CE", TODAY, EXPIRY,
            65, good.tickSize(), 1, "BUY", "INTRADAY",
            good.limitPrice(), 0, good.orderTag())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(new UpstoxFnoLimitOrderPayload.VerifiedParameters(
            good.underlyingKey(), good.instrumentKey(), "CE", TODAY, TODAY.minusDays(1),
            65, good.tickSize(), 1, "BUY", "INTRADAY",
            good.limitPrice(), 0, good.orderTag())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(candidate(
            "LONG_TERM", "BUY", 1, 0, "NSE_FO|43919")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(candidate(
            "INTRADAY", "BUY", 1, 0, "NSE_EQ|43919")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidTickQuantityAndTagFailClosed() {
        var good = candidate("INTRADAY", "BUY", 1, 0, "NSE_FO|43919");
        assertThatThrownBy(() -> builder.build(candidate(
            "INTRADAY", "BUY", 21, 0, good.instrumentKey())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(new UpstoxFnoLimitOrderPayload.VerifiedParameters(
            good.underlyingKey(), good.instrumentKey(), "CE", TODAY, EXPIRY,
            65, good.tickSize(), 1, "BUY", "INTRADAY",
            new BigDecimal("100.03"), 0, good.orderTag())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(new UpstoxFnoLimitOrderPayload.VerifiedParameters(
            good.underlyingKey(), good.instrumentKey(), "CE", TODAY, EXPIRY,
            65, good.tickSize(), 1, "BUY", "INTRADAY",
            good.limitPrice(), 0, "tag with spaces")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
