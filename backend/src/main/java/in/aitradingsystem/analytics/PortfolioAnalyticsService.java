package in.aitradingsystem.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PortfolioAnalyticsService {
    private final JdbcClient jdbc;
    private final Clock clock;
    public PortfolioAnalyticsService(JdbcClient jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public PortfolioAnalytics summarize(AnalyticsPeriod period) {
        List<EquityPoint> equity = jdbc.sql("""
            SELECT * FROM portfolio_performance_daily WHERE trade_date >= :from AND trade_date <= :to
             ORDER BY trade_date
            """).param("from", period.from()).param("to", period.to())
            .query((rs, row) -> new EquityPoint(rs.getObject("trade_date", LocalDate.class),
                rs.getBigDecimal("opening_equity"), rs.getBigDecimal("closing_equity"), rs.getBigDecimal("net_pnl"),
                rs.getBigDecimal("realized_pnl"), rs.getBigDecimal("fees"), rs.getBigDecimal("slippage"),
                rs.getBigDecimal("return_percent"), rs.getBigDecimal("max_intraday_drawdown_percent"),
                rs.getString("drawdown_basis"), rs.getObject("updated_at", OffsetDateTime.class).toInstant())).list();
        boolean complete = equity.size() == period.days();
        BigDecimal net = BigDecimal.ZERO, realized = BigDecimal.ZERO, fees = BigDecimal.ZERO, slippage = BigDecimal.ZERO;
        BigDecimal factor = BigDecimal.ONE;
        boolean returnsAvailable = complete;
        Instant latest = null;
        for (EquityPoint point : equity) {
            net = net.add(point.netPnl()); realized = realized.add(point.realizedPnl());
            fees = fees.add(point.fees()); slippage = slippage.add(point.slippage());
            if (point.returnPercent() == null) returnsAvailable = false;
            else factor = factor.multiply(BigDecimal.ONE.add(point.returnPercent().movePointLeft(2)));
            if (latest == null || latest.isBefore(point.materializedAt())) latest = point.materializedAt();
        }
        ExitMetrics exits = exitMetrics(period);
        List<DrawdownPoint> drawdowns = new ArrayList<>();
        if (returnsAvailable) {
            BigDecimal normalized = BigDecimal.ONE, peak = BigDecimal.ONE;
            for (EquityPoint point : equity) {
                normalized = normalized.multiply(BigDecimal.ONE.add(point.returnPercent().movePointLeft(2)));
                peak = peak.max(normalized);
                drawdowns.add(new DrawdownPoint(point.tradeDate(), percent(peak.subtract(normalized), peak)));
            }
        }
        Exposure exposure = jdbc.sql("""
            SELECT snapshot_at, gross_exposure, equity, open_positions FROM paper_portfolio_snapshot
             WHERE snapshot_at <= :now ORDER BY snapshot_at DESC LIMIT 1
            """).param("now", clock.instant().atOffset(java.time.ZoneOffset.UTC))
            .query((rs, row) -> new Exposure(rs.getObject("snapshot_at", OffsetDateTime.class).toInstant(),
                rs.getBigDecimal("gross_exposure"), rs.getBigDecimal("equity"), rs.getInt("open_positions"),
                percent(rs.getBigDecimal("gross_exposure"), rs.getBigDecimal("equity")))).optional().orElse(null);
        return new PortfolioAnalytics(period, complete, equity.size(), latest, money(net), money(realized), money(fees),
            money(slippage), returnsAvailable ? factor.subtract(BigDecimal.ONE).movePointRight(2).setScale(6, RoundingMode.HALF_UP) : null,
            "COMPOUNDED_DAILY_NET_ON_OPENING_EQUITY", exits, List.copyOf(equity), List.copyOf(drawdowns), exposure);
    }

    private ExitMetrics exitMetrics(AnalyticsPeriod period) {
        return jdbc.sql("""
            SELECT count(*) AS exits, count(*) FILTER (WHERE pnl > 0) AS wins,
                count(*) FILTER (WHERE pnl < 0) AS losses, COALESCE(sum(pnl),0) AS pnl
              FROM (SELECT order_id,sum(realized_pnl) AS pnl FROM paper_fill
                     WHERE side = 'SELL' AND filled_at >= :start AND filled_at < :end GROUP BY order_id) x
            """).param("start", period.start()).param("end", period.endExclusive())
            .query((rs, row) -> {
                long exits = rs.getLong("exits");
                return new ExitMetrics(exits, rs.getLong("wins"), rs.getLong("losses"),
                    percent(BigDecimal.valueOf(rs.getLong("wins")), BigDecimal.valueOf(exits)),
                    exits == 0 ? null : rs.getBigDecimal("pnl").divide(BigDecimal.valueOf(exits), 6, RoundingMode.HALF_UP));
            }).single();
    }

    static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() <= 0 ? null : numerator.multiply(new BigDecimal("100"))
            .divide(denominator, 6, RoundingMode.HALF_UP);
    }
    private static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    public record EquityPoint(LocalDate tradeDate, BigDecimal openingEquity, BigDecimal closingEquity,
        BigDecimal netPnl, BigDecimal realizedPnl, BigDecimal fees, BigDecimal slippage, BigDecimal returnPercent,
        BigDecimal sampledIntradayDrawdownPercent, String drawdownBasis, Instant materializedAt) {}
    public record DrawdownPoint(LocalDate tradeDate, BigDecimal dailyCloseDrawdownPercent) {}
    public record ExitMetrics(long closedExitOrders, long wins, long losses, BigDecimal winRatePercent,
                              BigDecimal realizedExpectancyPerExit) {}
    public record Exposure(Instant asOf, BigDecimal grossExposure, BigDecimal equity, int openPositions,
                           BigDecimal exposurePercent) {}
    public record PortfolioAnalytics(AnalyticsPeriod period, boolean materializationComplete, int materializedDays,
        Instant latestMaterializedAt, BigDecimal netPnl, BigDecimal realizedPnl, BigDecimal fees, BigDecimal slippage,
        BigDecimal compoundedDailyReturnPercent, String returnBasis, ExitMetrics exitMetrics,
        List<EquityPoint> equityCurve, List<DrawdownPoint> dailyCloseDrawdown, Exposure exposureSnapshot) {}
}
