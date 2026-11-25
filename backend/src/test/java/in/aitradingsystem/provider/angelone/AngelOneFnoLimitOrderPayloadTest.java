package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AngelOneFnoLimitOrderPayloadTest {
    private final AngelOneFnoLimitOrderPayload builder =
        new AngelOneFnoLimitOrderPayload(new ObjectMapper());

    private AngelOneFnoLimitOrderPayload.VerifiedParameters option(
            String horizon, String side, int lots, int held) {
        return new AngelOneFnoLimitOrderPayload.VerifiedParameters(
            "NSE_INDEX|Nifty 50", "NIFTY17NOV2626500CE", "61001", "CE",
            LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-17"),
            65, new BigDecimal(".05"), lots, side, horizon,
            new BigDecimal("100.05"), held, "MintTick_181");
    }

    @Test
    void intradayLongCallUsesNfoAndOptionUnits() {
        var payload = builder.build(option("INTRADAY", "BUY", 2, 0));
        assertThat(payload.path("exchange").asText()).isEqualTo("NFO");
        assertThat(payload.path("producttype").asText()).isEqualTo("INTRADAY");
        assertThat(payload.path("ordertype").asText()).isEqualTo("LIMIT");
        assertThat(payload.path("duration").asText()).isEqualTo("DAY");
        assertThat(payload.path("quantity").asText()).isEqualTo("130");
        assertThat(payload.path("symboltoken").asText()).isEqualTo("61001");
        assertThat(payload.path("price").asText()).isEqualTo("100.05");
    }

    @Test
    void positionalCloseCannotSellUnownedOptions() {
        var payload = builder.build(option("SWING", "SELL", 1, 65));
        assertThat(payload.path("producttype").asText()).isEqualTo("CARRYFORWARD");
        assertThat(payload.path("quantity").asText()).isEqualTo("65");
        assertThatThrownBy(() -> builder.build(option("SWING","SELL",2,65)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Naked short");
        assertThatThrownBy(() -> builder.build(option("SWING","SELL",1,0)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Naked short");
    }

    @Test
    void invalidContractTypeHorizonPriceAndLotFailClosed() {
        var g = option("INTRADAY","BUY",1,0);
        assertThatThrownBy(() -> builder.build(new AngelOneFnoLimitOrderPayload.VerifiedParameters(
            g.underlyingKey(), "BANKNIFTY17NOV2626500CE", g.symbolToken(),
            g.optionType(), g.tradingDate(),g.expiry(),g.lotSize(),g.tickSize(),
            g.lots(),g.side(),g.focus(),g.limitPrice(),0,g.orderTag())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(new AngelOneFnoLimitOrderPayload.VerifiedParameters(
            g.underlyingKey(),g.tradingSymbol(),g.symbolToken(),"PE",
            g.tradingDate(),g.expiry(),g.lotSize(),g.tickSize(),
            g.lots(),g.side(),g.focus(),g.limitPrice(),0,g.orderTag())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(option("LONG_TERM","BUY",1,0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(option("INTRADAY","BUY",21,0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(new AngelOneFnoLimitOrderPayload.VerifiedParameters(
            g.underlyingKey(),g.tradingSymbol(),g.symbolToken(),g.optionType(),
            g.tradingDate(),g.expiry(),g.lotSize(),g.tickSize(),
            g.lots(),g.side(),g.focus(),new BigDecimal("100.03"),0,g.orderTag())))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
