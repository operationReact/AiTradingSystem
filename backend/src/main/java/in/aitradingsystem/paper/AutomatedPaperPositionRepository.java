package in.aitradingsystem.paper;

import in.aitradingsystem.domain.ProductType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only projection of persisted PAPER positions plus their latest available market mark. */
@Repository
public class AutomatedPaperPositionRepository {
    private final JdbcClient jdbc;

    public AutomatedPaperPositionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<MonitoredPosition> openPositions(Instant asOf) {
        if (asOf == null) throw new IllegalArgumentException("asOf is required");
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(asOf, java.time.ZoneOffset.UTC);
        return jdbc.sql("""
                SELECT p.instrument_id, p.exchange, p.symbol, p.product_type, p.quantity,
                       p.stop_loss, p.target_price, p.trailing_percent, p.high_water_mark,
                       p.high_water_at, p.effective_protective_stop, p.trailing_version, p.updated_at,
                       COALESCE(
                           (SELECT mc.close_price
                              FROM market_candle mc
                             WHERE p.instrument_id IS NOT NULL
                               AND mc.instrument_id = p.instrument_id
                               AND mc.candle_time <= :asOf
                             ORDER BY mc.candle_time DESC, mc.candle_id DESC
                             LIMIT 1),
                           (SELECT pf.fill_price
                              FROM paper_fill pf
                             WHERE pf.symbol = p.symbol
                               AND pf.filled_at <= :asOf
                             ORDER BY pf.filled_at DESC, pf.fill_sequence DESC, pf.id DESC
                             LIMIT 1),
                           p.average_price,
                           p.cost_basis / p.quantity
                       ) AS mark_price,
                       COALESCE(
                           (SELECT mc.candle_time
                              FROM market_candle mc
                             WHERE p.instrument_id IS NOT NULL
                               AND mc.instrument_id = p.instrument_id
                               AND mc.candle_time <= :asOf
                             ORDER BY mc.candle_time DESC, mc.candle_id DESC
                             LIMIT 1),
                           (SELECT pf.filled_at
                              FROM paper_fill pf
                             WHERE pf.symbol = p.symbol
                               AND pf.filled_at <= :asOf
                             ORDER BY pf.filled_at DESC, pf.fill_sequence DESC, pf.id DESC
                             LIMIT 1),
                           p.updated_at
                       ) AS mark_at
                  FROM paper_position p
                 WHERE p.quantity > 0
                   AND (p.stop_loss IS NOT NULL OR p.target_price IS NOT NULL OR p.trailing_percent IS NOT NULL)
                 ORDER BY p.symbol, p.product_type
                """)
            .param("asOf", timestamp)
            .query((rs, row) -> new MonitoredPosition(
                rs.getObject("instrument_id", Long.class),
                rs.getString("exchange"),
                rs.getString("symbol"),
                ProductType.valueOf(rs.getString("product_type")),
                rs.getInt("quantity"),
                rs.getBigDecimal("stop_loss"),
                rs.getBigDecimal("target_price"),
                rs.getBigDecimal("trailing_percent"),
                rs.getBigDecimal("high_water_mark"),
                nullableInstant(rs.getObject("high_water_at", OffsetDateTime.class)),
                rs.getBigDecimal("effective_protective_stop"),
                rs.getLong("trailing_version"),
                rs.getBigDecimal("mark_price"),
                rs.getObject("mark_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
            .list();
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record MonitoredPosition(
        Long instrumentId,
        String exchange,
        String symbol,
        ProductType productType,
        int quantity,
        BigDecimal stopLoss,
        BigDecimal targetPrice,
        BigDecimal trailingPercent,
        BigDecimal highWaterMark,
        Instant highWaterAt,
        BigDecimal effectiveProtectiveStop,
        long trailingVersion,
        BigDecimal markPrice,
        Instant markAt,
        Instant positionUpdatedAt
    ) {
        /** Compatibility constructor for fixed stop/target monitor tests/callers. */
        public MonitoredPosition(Long instrumentId, String exchange, String symbol, ProductType productType,
                                 int quantity, BigDecimal stopLoss, BigDecimal targetPrice,
                                 BigDecimal markPrice, Instant markAt, Instant positionUpdatedAt) {
            this(instrumentId, exchange, symbol, productType, quantity, stopLoss, targetPrice,
                null, null, null, null, 0L, markPrice, markAt, positionUpdatedAt);
        }
    }
}
