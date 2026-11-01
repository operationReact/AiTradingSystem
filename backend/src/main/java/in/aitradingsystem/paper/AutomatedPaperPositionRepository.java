package in.aitradingsystem.paper;

import in.aitradingsystem.domain.ProductType;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persisted PAPER positions plus their latest available market mark for automated supervision. */
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
                       p.stop_loss, p.target_price, p.trailing_stop_percent, p.high_water_mark,
                       p.high_water_at, p.effective_protective_stop, p.updated_at,
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
                   AND (p.stop_loss IS NOT NULL OR p.target_price IS NOT NULL OR p.trailing_stop_percent IS NOT NULL)
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
                rs.getBigDecimal("trailing_stop_percent"),
                rs.getBigDecimal("high_water_mark"),
                instant(rs.getObject("high_water_at", OffsetDateTime.class)),
                rs.getBigDecimal("effective_protective_stop"),
                rs.getBigDecimal("mark_price"),
                instant(rs.getObject("mark_at", OffsetDateTime.class)),
                instant(rs.getObject("updated_at", OffsetDateTime.class))))
            .list();
    }

    /**
     * Advance trailing state only when the supplied mark creates a strictly newer high-water value.
     * The quantity and trailing percentage act as a lightweight optimistic lease around the persisted position.
     */
    public boolean advanceTrailingState(MonitoredPosition position, BigDecimal highWaterMark, Instant highWaterAt,
                                        BigDecimal effectiveProtectiveStop, Instant now) {
        if (position.trailingStopPercent() == null) return false;
        int updated = jdbc.sql("""
                UPDATE paper_position
                   SET high_water_mark = :highWaterMark,
                       high_water_at = :highWaterAt,
                       effective_protective_stop = :effectiveProtectiveStop,
                       updated_at = :now
                 WHERE symbol = :symbol
                   AND product_type = :productType
                   AND quantity = :quantity
                   AND trailing_stop_percent = :trailingStopPercent
                   AND (high_water_mark IS NULL OR high_water_mark < :highWaterMark)
                """)
            .param("highWaterMark", highWaterMark)
            .param("highWaterAt", PaperOrderRepository.ts(highWaterAt))
            .param("effectiveProtectiveStop", effectiveProtectiveStop)
            .param("now", PaperOrderRepository.ts(now))
            .param("symbol", position.symbol())
            .param("productType", position.productType().name())
            .param("quantity", position.quantity())
            .param("trailingStopPercent", position.trailingStopPercent(), Types.NUMERIC)
            .update();
        return updated == 1;
    }

    private static Instant instant(OffsetDateTime value) {
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
        BigDecimal trailingStopPercent,
        BigDecimal highWaterMark,
        Instant highWaterAt,
        BigDecimal effectiveProtectiveStop,
        BigDecimal markPrice,
        Instant markAt,
        Instant positionUpdatedAt
    ) {
        public MonitoredPosition(Long instrumentId, String exchange, String symbol, ProductType productType,
                                 int quantity, BigDecimal stopLoss, BigDecimal targetPrice, BigDecimal markPrice,
                                 Instant markAt, Instant positionUpdatedAt) {
            this(instrumentId, exchange, symbol, productType, quantity, stopLoss, targetPrice,
                null, null, null, null, markPrice, markAt, positionUpdatedAt);
        }

        MonitoredPosition withTrailingState(BigDecimal highWaterMark, Instant highWaterAt,
                                            BigDecimal effectiveProtectiveStop, Instant updatedAt) {
            return new MonitoredPosition(instrumentId, exchange, symbol, productType, quantity, stopLoss, targetPrice,
                trailingStopPercent, highWaterMark, highWaterAt, effectiveProtectiveStop, markPrice, markAt, updatedAt);
        }
    }
}
