package in.aitradingsystem.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ResearchAnalyticsService {
    private final JdbcClient jdbc;
    public ResearchAnalyticsService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public ResearchAnalytics summarize(AnalyticsPeriod period) {
        ScanMetrics scans = jdbc.sql("""
            SELECT count(*) runs,COALESCE(sum(scanned_count),0) scanned,COALESCE(sum(candidate_count),0) candidates,
                count(*) FILTER (WHERE status = 'FAILED') failed FROM research_scan_run
             WHERE started_at >= :start AND started_at < :end
            """).param("start", period.start()).param("end", period.endExclusive()).query((rs, row) -> {
                long scanned = rs.getLong("scanned"), candidates = rs.getLong("candidates");
                return new ScanMetrics(rs.getLong("runs"), scanned, candidates, rs.getLong("failed"),
                    scanned == 0 ? null : BigDecimal.valueOf(candidates).divide(BigDecimal.valueOf(scanned), 6, RoundingMode.HALF_UP));
            }).single();
        List<EvaluationCount> evaluations = jdbc.sql("""
            SELECT decision_label,count(*) count FROM research_evaluation WHERE created_at >= :start AND created_at < :end
             GROUP BY decision_label ORDER BY decision_label
            """).param("start", period.start()).param("end", period.endExclusive())
            .query((rs, row) -> new EvaluationCount(rs.getString("decision_label"), rs.getLong("count"))).list();
        BacktestMetrics backtests = jdbc.sql("""
            SELECT count(*) runs,COALESCE(sum(trade_count),0) trades,avg(total_return_percent) mean_return,
                avg(max_drawdown_percent) mean_drawdown FROM paper_backtest_run WHERE created_at >= :start AND created_at < :end
            """).param("start", period.start()).param("end", period.endExclusive())
            .query((rs, row) -> new BacktestMetrics(rs.getLong("runs"), rs.getLong("trades"),
                rs.getBigDecimal("mean_return"), rs.getBigDecimal("mean_drawdown"))).single();
        ScenarioMetrics scenarios = jdbc.sql("""
            SELECT count(*) runs,count(*) FILTER (WHERE status = 'SUCCEEDED') succeeded,
                count(*) FILTER (WHERE status = 'FAILED') failed,
                avg(expected_r) FILTER (WHERE status = 'SUCCEEDED') mean_expected_r
              FROM research_scenario_run WHERE created_at >= :start AND created_at < :end
            """).param("start", period.start()).param("end", period.endExclusive())
            .query((rs, row) -> new ScenarioMetrics(rs.getLong("runs"), rs.getLong("succeeded"), rs.getLong("failed"),
                rs.getBigDecimal("mean_expected_r"))).single();
        return new ResearchAnalytics(period, true, false, scans, evaluations, backtests, scenarios);
    }

    public record ScanMetrics(long runs, long scannedInstruments, long reportedCandidates, long failedRuns,
                              BigDecimal candidatesPerScannedInstrument) {}
    public record EvaluationCount(String label, long evaluations) {}
    public record BacktestMetrics(long runs, long simulatedTrades, BigDecimal unweightedMeanRunReturnPercent,
                                 BigDecimal unweightedMeanRunDrawdownPercent) {}
    public record ScenarioMetrics(long runs, long succeeded, long failed, BigDecimal meanSucceededExpectedR) {}
    public record ResearchAnalytics(AnalyticsPeriod period, boolean advisoryOnly, boolean profitabilityForecast,
        ScanMetrics scans, List<EvaluationCount> evaluationDistribution, BacktestMetrics backtests, ScenarioMetrics scenarios) {}
}
