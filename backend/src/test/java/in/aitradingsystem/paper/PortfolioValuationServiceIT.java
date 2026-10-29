package in.aitradingsystem.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class PortfolioValuationServiceIT extends AbstractPostgresIT {
    private static final String SYMBOL = "P6SNAP";

    @Autowired PortfolioValuationService valuation;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM paper_portfolio_snapshot").update();
        jdbc.sql("DELETE FROM paper_position WHERE symbol = :symbol").param("symbol", SYMBOL).update();
    }

    @Test
    void persistsDeterministicSnapshotFromFreshPersistedReference() {
        Instant asOf = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        seedPosition(asOf.minusSeconds(1));
        BigDecimal cash = jdbc.sql("SELECT cash_balance FROM paper_account WHERE id = 1")
            .query(BigDecimal.class).single();

        var snapshot = valuation.valueAndPersist(asOf);

        assertThat(snapshot.marketValue()).isEqualByComparingTo("200.00");
        assertThat(snapshot.equity()).isEqualByComparingTo(cash.add(new BigDecimal("200.00")));
        assertThat(snapshot.unrealizedPnl()).isEqualByComparingTo("0.00");
        assertThat(snapshot.grossExposure()).isEqualByComparingTo("200.00");
        assertThat(snapshot.stale()).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM paper_portfolio_snapshot WHERE id = :id")
            .param("id", snapshot.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void exposesStaleValuationWhenReferenceIsOld() {
        Instant asOf = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        seedPosition(asOf.minusSeconds(86_400));

        var snapshot = valuation.valueAndPersist(asOf);

        assertThat(snapshot.stale()).isTrue();
        assertThat(snapshot.stalePositions()).containsExactly(SYMBOL + ":DELIVERY");
    }

    @Test
    void snapshotTimestampIsControlledAndCannotBeDuplicated() {
        Instant asOf = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        seedPosition(asOf);
        valuation.valueAndPersist(asOf);

        assertThatThrownBy(() -> valuation.valueAndPersist(asOf))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void seedPosition(Instant updatedAt) {
        jdbc.sql("""
                INSERT INTO paper_position (
                    instrument_id, exchange, symbol, product_type, quantity, cost_basis, average_price,
                    realized_pnl, opened_at, updated_at)
                VALUES (NULL, 'PAPER', :symbol, 'DELIVERY', 2, 200.00, 100.000000, 0, :updatedAt, :updatedAt)
                """)
            .param("symbol", SYMBOL)
            .param("updatedAt", OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC))
            .update();
    }
}
