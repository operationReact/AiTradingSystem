package in.aitradingsystem.paper;

import in.aitradingsystem.domain.ProductType;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaperPositionRepository {
    private final JdbcClient jdbc;

    public PaperPositionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public PositionRow lock(Long instrumentId, String symbol, ProductType productType) {
        return jdbc.sql("""
                SELECT instrument_id, exchange, symbol, product_type, quantity, cost_basis, average_price, realized_pnl,
                       stop_loss, target_price, trailing_percent, high_water_mark, high_water_at,
                       effective_protective_stop, trailing_version
                  FROM paper_position
                 WHERE product_type = :productType
                   AND ((:instrumentId IS NOT NULL AND instrument_id = :instrumentId)
                        OR (:instrumentId IS NULL AND instrument_id IS NULL AND symbol = :symbol))
                 FOR UPDATE
                """)
            .param("productType", productType.name()).param("instrumentId", instrumentId, Types.BIGINT)
            .param("symbol", symbol)
            .query((rs, row) -> new PositionRow(rs.getObject("instrument_id", Long.class), rs.getString("exchange"),
                rs.getString("symbol"), ProductType.valueOf(rs.getString("product_type")), rs.getInt("quantity"),
                rs.getBigDecimal("cost_basis"), rs.getBigDecimal("average_price"), rs.getBigDecimal("realized_pnl"),
                rs.getBigDecimal("stop_loss"), rs.getBigDecimal("target_price"), rs.getBigDecimal("trailing_percent"),
                rs.getBigDecimal("high_water_mark"), PaperOrderRepository.instant(rs, "high_water_at"),
                rs.getBigDecimal("effective_protective_stop"), rs.getLong("trailing_version")))
            .optional().orElse(null);
    }

    public void insert(Long instrumentId, String exchange, String symbol, ProductType productType, int quantity,
                       BigDecimal costBasis, BigDecimal averagePrice, BigDecimal stopLoss, BigDecimal targetPrice,
                       BigDecimal trailingPercent, BigDecimal highWaterMark, Instant highWaterAt,
                       BigDecimal effectiveProtectiveStop, Instant now) {
        jdbc.sql("""
                INSERT INTO paper_position (
                    instrument_id, exchange, symbol, product_type, quantity, cost_basis, average_price,
                    stop_loss, target_price, trailing_percent, high_water_mark, high_water_at,
                    effective_protective_stop, trailing_version, realized_pnl, opened_at, updated_at)
                VALUES (:instrumentId, :exchange, :symbol, :productType, :quantity, :costBasis, :averagePrice,
                    :stopLoss, :targetPrice, :trailingPercent, :highWaterMark, :highWaterAt,
                    :effectiveProtectiveStop, 0, 0, :now, :now)
                """)
            .param("instrumentId", instrumentId, Types.BIGINT).param("exchange", exchange, Types.VARCHAR)
            .param("symbol", symbol).param("productType", productType.name()).param("quantity", quantity)
            .param("costBasis", costBasis).param("averagePrice", averagePrice)
            .param("stopLoss", stopLoss, Types.NUMERIC).param("targetPrice", targetPrice, Types.NUMERIC)
            .param("trailingPercent", trailingPercent, Types.NUMERIC)
            .param("highWaterMark", highWaterMark, Types.NUMERIC)
            .param("highWaterAt", highWaterAt == null ? null : PaperOrderRepository.ts(highWaterAt), Types.TIMESTAMP_WITH_TIMEZONE)
            .param("effectiveProtectiveStop", effectiveProtectiveStop, Types.NUMERIC)
            .param("now", PaperOrderRepository.ts(now)).update();
    }

    /** Compatibility insert used by non-trailing accounting tests/callers. */
    public void insert(Long instrumentId, String exchange, String symbol, ProductType productType, int quantity,
                       BigDecimal costBasis, BigDecimal averagePrice, BigDecimal stopLoss, BigDecimal targetPrice,
                       Instant now) {
        insert(instrumentId, exchange, symbol, productType, quantity, costBasis, averagePrice, stopLoss, targetPrice,
            null, null, null, null, now);
    }

    public boolean update(PositionRow expected, Long instrumentId, String exchange, int quantity,
                          BigDecimal costBasis, BigDecimal averagePrice, BigDecimal realizedPnl, Instant now) {
        int updated = jdbc.sql("""
                UPDATE paper_position
                   SET instrument_id = COALESCE(:instrumentId, instrument_id),
                       exchange = COALESCE(:exchange, exchange), quantity = :quantity,
                       cost_basis = :costBasis, average_price = :averagePrice,
                       realized_pnl = :realizedPnl, updated_at = :now
                 WHERE symbol = :symbol AND product_type = :productType AND quantity = :expectedQuantity
                """)
            .param("instrumentId", instrumentId, Types.BIGINT).param("exchange", exchange, Types.VARCHAR)
            .param("quantity", quantity).param("costBasis", costBasis).param("averagePrice", averagePrice, Types.NUMERIC)
            .param("realizedPnl", realizedPnl).param("now", PaperOrderRepository.ts(now))
            .param("symbol", expected.symbol()).param("productType", expected.productType().name())
            .param("expectedQuantity", expected.quantity()).update();
        return updated == 1;
    }

    /** Advances trailing state monotonically without mutating the accounting timestamp/version. */
    public boolean advanceTrailingState(String symbol, ProductType productType, long expectedTrailingVersion,
                                        BigDecimal highWaterMark, Instant highWaterAt,
                                        BigDecimal effectiveProtectiveStop) {
        int updated = jdbc.sql("""
                UPDATE paper_position
                   SET high_water_mark = :highWaterMark,
                       high_water_at = :highWaterAt,
                       effective_protective_stop = :effectiveProtectiveStop,
                       trailing_version = trailing_version + 1
                 WHERE symbol = :symbol
                   AND product_type = :productType
                   AND trailing_percent IS NOT NULL
                   AND trailing_version = :expectedTrailingVersion
                """)
            .param("highWaterMark", highWaterMark)
            .param("highWaterAt", PaperOrderRepository.ts(highWaterAt))
            .param("effectiveProtectiveStop", effectiveProtectiveStop)
            .param("symbol", symbol)
            .param("productType", productType.name())
            .param("expectedTrailingVersion", expectedTrailingVersion)
            .update();
        return updated == 1;
    }

    public boolean delete(PositionRow expected) {
        return jdbc.sql("DELETE FROM paper_position WHERE symbol = :symbol AND product_type = :productType AND quantity = :expectedQuantity")
            .param("symbol", expected.symbol()).param("productType", expected.productType().name())
            .param("expectedQuantity", expected.quantity()).update() == 1;
    }

    public record PositionRow(Long instrumentId, String exchange, String symbol, ProductType productType,
                              int quantity, BigDecimal costBasis, BigDecimal averagePrice, BigDecimal realizedPnl,
                              BigDecimal stopLoss, BigDecimal targetPrice, BigDecimal trailingPercent,
                              BigDecimal highWaterMark, Instant highWaterAt, BigDecimal effectiveProtectiveStop,
                              long trailingVersion) {
        /** Compatibility constructor for pre-trailing accounting tests/callers. */
        public PositionRow(Long instrumentId, String exchange, String symbol, ProductType productType,
                           int quantity, BigDecimal costBasis, BigDecimal averagePrice, BigDecimal realizedPnl,
                           BigDecimal stopLoss, BigDecimal targetPrice) {
            this(instrumentId, exchange, symbol, productType, quantity, costBasis, averagePrice, realizedPnl,
                stopLoss, targetPrice, null, null, null, null, 0L);
        }
    }
}
