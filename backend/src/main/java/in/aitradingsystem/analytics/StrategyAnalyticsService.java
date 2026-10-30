package in.aitradingsystem.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class StrategyAnalyticsService {
    private final JdbcClient jdbc;
    public StrategyAnalyticsService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<StrategyPerformance> summarize(AnalyticsPeriod period) {
        List<StrategyPerformance> rows = jdbc.sql("""
            WITH perf AS (
                SELECT strategy,sum(net_pnl) net,sum(realized_pnl) realized,sum(fees) fees,sum(slippage) slippage,
                    sum(trade_count) trades,sum(win_count) wins,sum(loss_count) losses,max(updated_at) materialized_at
                  FROM strategy_performance_daily WHERE trade_date >= :from AND trade_date <= :to GROUP BY strategy
            ), cohort AS (
                SELECT c.strategy,count(*) candidates,
                    count(*) FILTER (WHERE EXISTS (SELECT 1 FROM research_evaluation e WHERE e.candidate_id = c.id
                        AND e.created_at >= c.created_at AND e.created_at < :end)) evaluated
                  FROM research_scan_candidate c WHERE c.created_at >= :start AND c.created_at < :end GROUP BY c.strategy
            )
            SELECT COALESCE(p.strategy,c.strategy) strategy,COALESCE(p.net,0) net,COALESCE(p.realized,0) realized,
                COALESCE(p.fees,0) fees,COALESCE(p.slippage,0) slippage,COALESCE(p.trades,0) trades,
                COALESCE(p.wins,0) wins,COALESCE(p.losses,0) losses,p.materialized_at,
                COALESCE(c.candidates,0) candidates,COALESCE(c.evaluated,0) evaluated
              FROM perf p FULL OUTER JOIN cohort c ON p.strategy = c.strategy ORDER BY strategy LIMIT 1001
            """).param("from", period.from()).param("to", period.to()).param("start", period.start())
            .param("end", period.endExclusive()).query((rs, row) -> {
                OffsetDateTime at = rs.getObject("materialized_at", OffsetDateTime.class);
                long candidates = rs.getLong("candidates"), evaluated = rs.getLong("evaluated");
                return new StrategyPerformance(rs.getString("strategy"), rs.getBigDecimal("net"),
                    rs.getBigDecimal("realized"), rs.getBigDecimal("fees"), rs.getBigDecimal("slippage"),
                    rs.getLong("trades"), rs.getLong("wins"), rs.getLong("losses"), at == null ? null : at.toInstant(),
                    candidates, evaluated, PortfolioAnalyticsService.percent(BigDecimal.valueOf(evaluated), BigDecimal.valueOf(candidates)));
            }).list();
        if (rows.size() > 1000) throw new IllegalArgumentException("Narrow analytics period: more than 1000 strategy groups");
        return List.copyOf(rows);
    }

    public record StrategyPerformance(String strategy, BigDecimal netPnl, BigDecimal realizedPnl, BigDecimal fees,
        BigDecimal slippage, long executedOrderDays, long winningExitDays, long losingExitDays, Instant latestMaterializedAt,
        long cohortCandidates, long evaluatedCohortCandidates, BigDecimal cohortEvaluationPercent) {}
}
