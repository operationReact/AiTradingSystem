package in.aitradingsystem.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class IntradayPaperCloseServiceIT extends AbstractPostgresIT {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String SYMBOL = "P6AUTO";

    @Autowired IntradayPaperCloseService service;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanup() {
        jdbc.sql("UPDATE trading_control SET new_orders_halted = FALSE, reason = NULL WHERE id = 1").update();
        jdbc.sql("DELETE FROM risk_decision_rule WHERE risk_decision_id IN (SELECT d.id FROM risk_decision d JOIN paper_order o ON o.id = d.order_id WHERE o.symbol = :symbol)")
            .param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM risk_decision WHERE order_id IN (SELECT id FROM paper_order WHERE symbol = :symbol)")
            .param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM audit_event WHERE entity_type = 'PAPER_ORDER' AND entity_id IN (SELECT id::text FROM paper_order WHERE symbol = :symbol)")
            .param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM paper_order WHERE symbol = :symbol").param("symbol", SYMBOL).update();
        jdbc.sql("DELETE FROM paper_position WHERE symbol = :symbol").param("symbol", SYMBOL).update();
    }

    @Test
    void beforeCutoffCreatesNoIntent() {
        seedIntradayPosition();
        Instant before = ZonedDateTime.of(LocalDate.of(2026, 10, 29), LocalTime.of(15, 9), IST).toInstant();

        var result = service.closeEligiblePositions(before);

        assertThat(result.positionsFound()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM paper_order WHERE symbol = :symbol")
            .param("symbol", SYMBOL).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM paper_position WHERE symbol = :symbol AND product_type = 'INTRADAY'")
            .param("symbol", SYMBOL).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void afterCutoffCreatesOneIdempotentSystemIntentThroughNormalRiskPath() {
        seedIntradayPosition();
        jdbc.sql("UPDATE trading_control SET new_orders_halted = TRUE, reason = 'test deterministic rejection' WHERE id = 1").update();
        Instant after = ZonedDateTime.of(LocalDate.of(2026, 10, 29), LocalTime.of(15, 15), IST).toInstant();

        var first = service.closeEligiblePositions(after);
        var second = service.closeEligiblePositions(after);

        assertThat(first.positionsFound()).isEqualTo(1);
        assertThat(second.positionsFound()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM paper_order WHERE symbol = :symbol AND product_type = 'INTRADAY'")
            .param("symbol", SYMBOL).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM paper_order WHERE symbol = :symbol")
            .param("symbol", SYMBOL).query(String.class).single()).isEqualTo("REJECTED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM risk_decision d JOIN paper_order o ON o.id = d.order_id WHERE o.symbol = :symbol")
            .param("symbol", SYMBOL).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_event WHERE event_type = 'INTRADAY_AUTO_CLOSE' AND entity_id IN (SELECT id::text FROM paper_order WHERE symbol = :symbol)")
            .param("symbol", SYMBOL).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM paper_position WHERE symbol = :symbol AND product_type = 'INTRADAY'")
            .param("symbol", SYMBOL).query(Integer.class).single()).isEqualTo(1);
        assertThat(IntradayPaperCloseService.idempotencyKey(
            LocalDate.of(2026, 10, 29), new IntradayPaperCloseService.IntradayPosition(null, SYMBOL, 5, java.math.BigDecimal.TEN)))
            .isEqualTo("intraday-close:2026-10-29:" + SYMBOL);
    }

    private void seedIntradayPosition() {
        jdbc.sql("""
                INSERT INTO paper_position (
                    instrument_id, exchange, symbol, product_type, quantity, cost_basis, average_price,
                    realized_pnl, opened_at, updated_at)
                VALUES (NULL, 'PAPER', :symbol, 'INTRADAY', 5, 500.00, 100.000000, 0, now(), now())
                """)
            .param("symbol", SYMBOL).update();
    }
}
