package in.aitradingsystem.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.domain.ProductType;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class PositionAccountingServiceIT extends AbstractPostgresIT {
    private static final String SYMBOL = "P6POS";

    @Autowired PositionAccountingService accounting;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM paper_position WHERE symbol = :symbol").param("symbol", SYMBOL).update();
    }

    @Test
    void weightedAverageAndPartialSellRealizedPnlAreDeterministic() {
        Instant now = Instant.parse("2026-10-29T10:00:00Z");

        accounting.applyBuy(null, "PAPER", SYMBOL, ProductType.DELIVERY, 10, new BigDecimal("1000.00"), now);
        var afterSecondBuy = accounting.applyBuy(
            null, "PAPER", SYMBOL, ProductType.DELIVERY, 10, new BigDecimal("1200.00"), now.plusSeconds(1));

        assertThat(afterSecondBuy.quantity()).isEqualTo(20);
        assertThat(afterSecondBuy.costBasis()).isEqualByComparingTo("2200.00");
        assertThat(afterSecondBuy.averagePrice()).isEqualByComparingTo("110.000000");

        var afterSell = accounting.applySell(
            null, SYMBOL, ProductType.DELIVERY, 5, new BigDecimal("600.00"), now.plusSeconds(2));

        assertThat(afterSell.quantity()).isEqualTo(15);
        assertThat(afterSell.costBasis()).isEqualByComparingTo("1650.00");
        assertThat(afterSell.averagePrice()).isEqualByComparingTo("110.000000");
        assertThat(afterSell.realizedPnl()).isEqualByComparingTo("50.00");
    }

    @Test
    void deliveryAndIntradayPositionsAreIndependent() {
        Instant now = Instant.parse("2026-10-29T10:00:00Z");
        accounting.applyBuy(null, "PAPER", SYMBOL, ProductType.DELIVERY, 3, new BigDecimal("300.00"), now);
        accounting.applyBuy(null, "PAPER", SYMBOL, ProductType.INTRADAY, 2, new BigDecimal("220.00"), now);

        Integer rows = jdbc.sql("SELECT COUNT(*) FROM paper_position WHERE symbol = :symbol")
            .param("symbol", SYMBOL).query(Integer.class).single();
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void rejectsShortPositionCreation() {
        assertThatThrownBy(() -> accounting.applySell(
            null, SYMBOL, ProductType.DELIVERY, 1, new BigDecimal("100.00"), Instant.now()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Short PAPER positions are not allowed");
    }
}
