package in.aitradingsystem.backtest;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BacktestAnalyticsRepository {
    private final JdbcClient client;
    private final JdbcTemplate jdbc;

    public BacktestAnalyticsRepository(JdbcClient client, JdbcTemplate jdbc) {
        this.client = client;
        this.jdbc = jdbc;
    }

    public void save(UUID runId, BacktestRunAnalytics analytics, List<BacktestEquityPoint> equityPoints) {
        client.sql("""
            UPDATE paper_backtest_run
               SET strategy_config_version = :strategyConfigVersion,
                   methodology_version = :methodologyVersion,
                   data_cutoff_at = :dataCutoffAt,
                   source_candle_count = :sourceCandleCount,
                   benchmark_return_percent = :benchmarkReturnPercent,
                   expectancy_r = :expectancyR,
                   average_r = :averageR,
                   exposure_percent = :exposurePercent,
                   turnover = :turnover,
                   max_consecutive_losses = :maxConsecutiveLosses,
                   assumptions_json = CAST(:assumptionsJson AS jsonb)
             WHERE id = :runId
            """)
            .param("strategyConfigVersion", analytics.strategyConfigVersion(), Types.INTEGER)
            .param("methodologyVersion", analytics.methodologyVersion())
            .param("dataCutoffAt", analytics.dataCutoffAt() == null ? null : timestamp(analytics.dataCutoffAt()), Types.TIMESTAMP_WITH_TIMEZONE)
            .param("sourceCandleCount", analytics.sourceCandleCount())
            .param("benchmarkReturnPercent", analytics.benchmarkReturnPercent(), Types.NUMERIC)
            .param("expectancyR", analytics.expectancyR(), Types.NUMERIC)
            .param("averageR", analytics.averageR(), Types.NUMERIC)
            .param("exposurePercent", analytics.exposurePercent(), Types.NUMERIC)
            .param("turnover", analytics.turnover(), Types.NUMERIC)
            .param("maxConsecutiveLosses", analytics.maxConsecutiveLosses())
            .param("assumptionsJson", analytics.assumptionsJson())
            .param("runId", runId)
            .update();

        if (equityPoints == null || equityPoints.isEmpty()) return;
        jdbc.batchUpdate("""
            INSERT INTO paper_backtest_equity_point (run_id, point_time, equity, cash, open_value)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (run_id, point_time) DO UPDATE
            SET equity = EXCLUDED.equity,
                cash = EXCLUDED.cash,
                open_value = EXCLUDED.open_value
            """, equityPoints, 500, (ps, point) -> {
                ps.setObject(1, runId);
                ps.setObject(2, timestamp(point.pointTime()));
                ps.setBigDecimal(3, point.equity());
                ps.setBigDecimal(4, point.cash());
                ps.setBigDecimal(5, point.openValue());
            });
    }

    public BacktestRunAnalytics requireAnalytics(UUID runId) {
        return client.sql("""
            SELECT strategy_config_version, methodology_version, data_cutoff_at, source_candle_count,
                   benchmark_return_percent, expectancy_r, average_r, exposure_percent, turnover,
                   max_consecutive_losses, assumptions_json::text AS assumptions_json
              FROM paper_backtest_run
             WHERE id = :runId
            """)
            .param("runId", runId)
            .query((rs, row) -> new BacktestRunAnalytics(
                    (Integer) rs.getObject("strategy_config_version"),
                    rs.getString("methodology_version"),
                    instant(rs.getObject("data_cutoff_at", OffsetDateTime.class)),
                    rs.getInt("source_candle_count"),
                    rs.getBigDecimal("benchmark_return_percent"),
                    rs.getBigDecimal("expectancy_r"),
                    rs.getBigDecimal("average_r"),
                    rs.getBigDecimal("exposure_percent"),
                    rs.getBigDecimal("turnover"),
                    rs.getInt("max_consecutive_losses"),
                    rs.getString("assumptions_json")))
            .optional()
            .orElseThrow(() -> new BacktestException("BACKTEST_NOT_FOUND", "Backtest run was not found"));
    }

    public List<BacktestEquityPoint> equityCurve(UUID runId) {
        return client.sql("""
            SELECT point_time, equity, cash, open_value
              FROM paper_backtest_equity_point
             WHERE run_id = :runId
             ORDER BY point_time ASC
            """)
            .param("runId", runId)
            .query((rs, row) -> new BacktestEquityPoint(
                    instant(rs.getObject("point_time", OffsetDateTime.class)),
                    rs.getBigDecimal("equity"),
                    rs.getBigDecimal("cash"),
                    rs.getBigDecimal("open_value")))
            .list();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
