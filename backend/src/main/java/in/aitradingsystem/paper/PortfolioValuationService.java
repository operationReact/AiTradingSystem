package in.aitradingsystem.paper;

import in.aitradingsystem.risk.RiskPolicyConfigRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Values the persisted PAPER book and records immutable point-in-time snapshots. */
@Service
public class PortfolioValuationService {
    private final JdbcClient jdbc;
    private final RiskPolicyConfigRepository policies;

    public PortfolioValuationService(JdbcClient jdbc, RiskPolicyConfigRepository policies) {
        this.jdbc = jdbc;
        this.policies = policies;
    }

    @Transactional
    public ValuationSnapshot valueAndPersist(Instant asOf) {
        if (asOf == null) throw new IllegalArgumentException("asOf is required");
        BigDecimal cash = jdbc.sql("SELECT cash_balance FROM paper_account WHERE id = 1")
            .query(BigDecimal.class).single();
        long maxAgeSeconds = policies.requireActive().marketStateMaxAgeSeconds();
        List<Position> positions = jdbc.sql("""
                SELECT instrument_id, symbol, product_type, quantity, cost_basis, average_price, updated_at
                  FROM paper_position
                 ORDER BY symbol, product_type
                """)
            .query((rs, row) -> new Position(
                rs.getObject("instrument_id", Long.class), rs.getString("symbol"), rs.getString("product_type"),
                rs.getInt("quantity"), rs.getBigDecimal("cost_basis"), rs.getBigDecimal("average_price"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
            .list();

        BigDecimal marketValue = BigDecimal.ZERO;
        BigDecimal costBasis = BigDecimal.ZERO;
        List<String> stalePositions = new ArrayList<>();
        for (Position position : positions) {
            PriceMark mark = mark(position, asOf);
            if (mark.stale(asOf, maxAgeSeconds)) {
                stalePositions.add(position.symbol() + ":" + position.productType());
            }
            marketValue = marketValue.add(mark.price().multiply(BigDecimal.valueOf(position.quantity())));
            costBasis = costBasis.add(position.costBasis());
        }

        marketValue = money(marketValue);
        costBasis = money(costBasis);
        BigDecimal equity = money(cash.add(marketValue));
        BigDecimal unrealized = money(marketValue.subtract(costBasis));
        BigDecimal realizedToday = money(realizedToday(asOf));
        BigDecimal grossExposure = marketValue.abs();

        long id = jdbc.sql("""
                INSERT INTO paper_portfolio_snapshot (
                    snapshot_at, cash, market_value, equity, realized_pnl_day,
                    unrealized_pnl, gross_exposure, open_positions)
                VALUES (:snapshotAt, :cash, :marketValue, :equity, :realized,
                    :unrealized, :grossExposure, :openPositions)
                RETURNING id
                """)
            .param("snapshotAt", ts(asOf))
            .param("cash", money(cash))
            .param("marketValue", marketValue)
            .param("equity", equity)
            .param("realized", realizedToday)
            .param("unrealized", unrealized)
            .param("grossExposure", grossExposure)
            .param("openPositions", positions.size())
            .query(Long.class).single();

        return new ValuationSnapshot(id, asOf, money(cash), marketValue, equity, realizedToday, unrealized,
            grossExposure, positions.size(), !stalePositions.isEmpty(), List.copyOf(stalePositions));
    }

    private PriceMark mark(Position position, Instant asOf) {
        if (position.instrumentId() != null) {
            PriceMark candle = jdbc.sql("""
                    SELECT close_price, candle_time
                      FROM market_candle
                     WHERE instrument_id = :instrumentId AND candle_time <= :asOf
                     ORDER BY candle_time DESC, candle_id DESC
                     LIMIT 1
                    """)
                .param("instrumentId", position.instrumentId())
                .param("asOf", ts(asOf))
                .query((rs, row) -> new PriceMark(
                    rs.getBigDecimal("close_price"), rs.getObject("candle_time", OffsetDateTime.class).toInstant()))
                .optional().orElse(null);
            if (candle != null) return candle;
        }

        PriceMark fill = jdbc.sql("""
                SELECT fill_price, filled_at
                  FROM paper_fill
                 WHERE symbol = :symbol AND filled_at <= :asOf
                 ORDER BY filled_at DESC, fill_sequence DESC, id DESC
                 LIMIT 1
                """)
            .param("symbol", position.symbol())
            .param("asOf", ts(asOf))
            .query((rs, row) -> new PriceMark(
                rs.getBigDecimal("fill_price"), rs.getObject("filled_at", OffsetDateTime.class).toInstant()))
            .optional().orElse(null);
        if (fill != null) return fill;

        BigDecimal fallback = position.averagePrice() != null
            ? position.averagePrice()
            : position.costBasis().divide(BigDecimal.valueOf(position.quantity()), 6, RoundingMode.HALF_UP);
        return new PriceMark(fallback, position.updatedAt());
    }

    private BigDecimal realizedToday(Instant asOf) {
        Instant start = asOf.atZone(PaperTradingService.MARKET_ZONE).toLocalDate()
            .atStartOfDay(PaperTradingService.MARKET_ZONE).toInstant();
        return jdbc.sql("""
                SELECT COALESCE(SUM(realized_pnl), 0)
                  FROM paper_fill
                 WHERE filled_at >= :start AND filled_at <= :asOf
                """)
            .param("start", ts(start))
            .param("asOf", ts(asOf))
            .query(BigDecimal.class).single();
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Position(Long instrumentId, String symbol, String productType, int quantity,
                            BigDecimal costBasis, BigDecimal averagePrice, Instant updatedAt) {}

    private record PriceMark(BigDecimal price, Instant asOf) {
        boolean stale(Instant valuationAt, long maxAgeSeconds) {
            if (asOf == null || asOf.isAfter(valuationAt)) return true;
            return Duration.between(asOf, valuationAt).getSeconds() > maxAgeSeconds;
        }
    }

    public record ValuationSnapshot(
        long id,
        Instant snapshotAt,
        BigDecimal cash,
        BigDecimal marketValue,
        BigDecimal equity,
        BigDecimal realizedPnlDay,
        BigDecimal unrealizedPnl,
        BigDecimal grossExposure,
        int openPositions,
        boolean stale,
        List<String> stalePositions
    ) {}
}
