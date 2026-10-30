package in.aitradingsystem.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class AnalyticsServicesIT extends AbstractPostgresIT {
    private static final LocalDate FROM = LocalDate.of(2002, 1, 8);
    private static final AnalyticsPeriod PERIOD = new AnalyticsPeriod(FROM, FROM.plusDays(1));
    @Autowired JdbcClient jdbc;
    @Autowired PortfolioAnalyticsService portfolio;
    @Autowired StrategyAnalyticsService strategies;
    @Autowired ResearchAnalyticsService research;
    private final List<UUID> orders = new ArrayList<>();
    private UUID scan;
    private UUID scenario;

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM portfolio_performance_daily WHERE trade_date BETWEEN :from AND :to")
            .param("from", PERIOD.from()).param("to", PERIOD.to()).update();
        jdbc.sql("DELETE FROM strategy_performance_daily WHERE trade_date BETWEEN :from AND :to")
            .param("from", PERIOD.from()).param("to", PERIOD.to()).update();
        for (UUID id : orders) {
            jdbc.sql("DELETE FROM paper_fill WHERE order_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM paper_order WHERE id = :id").param("id", id).update();
        }
        if (scenario != null) {
            jdbc.sql("DELETE FROM research_evaluation WHERE scenario_run_id = :id").param("id", scenario).update();
            jdbc.sql("DELETE FROM research_scenario_run WHERE id = :id").param("id", scenario).update();
        }
        if (scan != null) jdbc.sql("DELETE FROM research_scan_run WHERE id = :id").param("id", scan).update();
    }

    @Test
    void compoundsReturnsAndMeasuresDailyCloseDrawdownWithoutPartialFillInflation() {
        daily(FROM, "10000", "10100", "100", "1");
        daily(FROM.plusDays(1), "10100", "9898", "-202", "-2");
        exit("70", "30");
        exit("-202");
        var result = portfolio.summarize(PERIOD);
        assertThat(result.materializationComplete()).isTrue();
        assertThat(result.netPnl()).isEqualByComparingTo("-102");
        assertThat(result.compoundedDailyReturnPercent()).isEqualByComparingTo("-1.02");
        assertThat(result.dailyCloseDrawdown().get(1).dailyCloseDrawdownPercent()).isEqualByComparingTo("2");
        assertThat(result.exitMetrics().closedExitOrders()).isEqualTo(2);
        assertThat(result.exitMetrics().winRatePercent()).isEqualByComparingTo("50");
        assertThat(result.exitMetrics().realizedExpectancyPerExit()).isEqualByComparingTo("-51");
    }

    @Test
    void incompleteMaterializationDoesNotInventReturnOrBridgeDrawdownAcrossMissingDates() {
        daily(FROM, "10000", "10100", "100", "1");
        var result = portfolio.summarize(PERIOD);
        assertThat(result.materializationComplete()).isFalse();
        assertThat(result.materializedDays()).isEqualTo(1);
        assertThat(result.compoundedDailyReturnPercent()).isNull();
        assertThat(result.dailyCloseDrawdown()).isEmpty();
        assertThat(result.exitMetrics().winRatePercent()).isNull();
        assertThat(result.exitMetrics().realizedExpectancyPerExit()).isNull();
    }

    @Test
    void researchCohortConversionDeduplicatesEvaluationsAndDoesNotPromiseProfitability() {
        daily(FROM, "10000", "10100", "100", "1");
        seedResearch();
        var strategy = strategies.summarize(PERIOD).stream().filter(s -> s.strategy().equals("P8S")).findFirst().orElseThrow();
        assertThat(strategy.cohortCandidates()).isEqualTo(2);
        assertThat(strategy.evaluatedCohortCandidates()).isEqualTo(1);
        assertThat(strategy.cohortEvaluationPercent()).isEqualByComparingTo("50");
        assertThat(strategy.netPnl()).isEqualByComparingTo("100");
        var result = research.summarize(PERIOD);
        assertThat(result.advisoryOnly()).isTrue();
        assertThat(result.profitabilityForecast()).isFalse();
        // Multiple strategies can emit candidates for one instrument; this is a yield ratio, not a probability.
        assertThat(result.scans().candidatesPerScannedInstrument()).isEqualByComparingTo("2");
        assertThat(result.evaluationDistribution()).hasSize(2);
        assertThat(result.backtests().unweightedMeanRunReturnPercent()).isNull();
    }

    @Test
    void validatesBoundedRangesAndUsesHalfOpenAsiaKolkataBoundaries() {
        assertThat(PERIOD.start().getOffset().getTotalSeconds()).isEqualTo(19800);
        assertThat(PERIOD.endExclusive().toLocalDate()).isEqualTo(FROM.plusDays(2));
        assertThatThrownBy(() -> new AnalyticsPeriod(FROM, FROM.minusDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalyticsPeriod(FROM, FROM.plusDays(366))).isInstanceOf(IllegalArgumentException.class);
    }

    private void daily(LocalDate day, String opening, String closing, String pnl, String returns) {
        jdbc.sql("""
            INSERT INTO portfolio_performance_daily(trade_date,opening_equity,closing_equity,capital_flow,
                realized_pnl,unrealized_pnl,fees,slippage,gross_pnl,net_pnl,return_percent,drawdown_basis,
                trade_count,win_count,loss_count,valuation_evidence)
            VALUES (:day,:opening,:closing,0,:pnl,0,1,1,:pnl,:pnl,:returns,'UNAVAILABLE',2,1,0,'[]')
            """).param("day", day).param("opening", new BigDecimal(opening)).param("closing", new BigDecimal(closing))
            .param("pnl", new BigDecimal(pnl)).param("returns", new BigDecimal(returns)).update();
        jdbc.sql("""
            INSERT INTO strategy_performance_daily(trade_date,strategy,realized_pnl,unrealized_pnl,fees,slippage,
                gross_pnl,net_pnl,trade_count,win_count,loss_count)
            VALUES (:day,'P8S',:pnl,0,1,1,:pnl,:pnl,2,1,0)
            """).param("day", day).param("pnl", new BigDecimal(pnl)).update();
    }

    private void exit(String... pnl) {
        UUID id = UUID.randomUUID(); orders.add(id);
        jdbc.sql("""
            INSERT INTO paper_order(id,idempotency_key,request_hash,symbol,side,quantity,reference_price,status,strategy)
            VALUES (:id,:key,:hash,'P8READ','SELL',2,100,'FILLED','P8S')
            """).param("id", id).param("key", "p8-read-" + id).param("hash", "0".repeat(64)).update();
        for (int sequence = 1; sequence <= pnl.length; sequence++) {
            jdbc.sql("""
                INSERT INTO paper_fill(id,order_id,symbol,side,quantity,reference_price,fill_price,gross_amount,
                    fee_amount,slippage_amount,net_amount,cost_basis,realized_pnl,filled_at,fill_sequence)
                VALUES (:id,:order,'P8READ','SELL',1,100,100,100,0,0,100,0,:pnl,:at,:sequence)
                """).param("id", UUID.randomUUID()).param("order", id).param("pnl", new BigDecimal(pnl[sequence - 1]))
                .param("at", PERIOD.start().plusHours(10)).param("sequence", sequence).update();
        }
    }

    private void seedResearch() {
        scan = UUID.randomUUID(); scenario = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO research_scan_run(id,interval,status,requested_limit,scanned_count,candidate_count,strategy_set,started_at)
            VALUES (:id,'ONE_MINUTE','SUCCEEDED',2,2,4,'P8S',:at)
            """).param("id", scan).param("at", PERIOD.start().plusHours(9)).update();
        long candidate = 0;
        for (int i = 1; i <= 2; i++) {
            long id = jdbc.sql("""
                INSERT INTO research_scan_candidate(scan_run_id,instrument_id,trading_symbol,exchange,interval,strategy,
                    score,entry_price,stop_price,target_price,signal_candle_time,reasons,created_at)
                VALUES (:scan,:instrument,'P8READ','NSE','ONE_MINUTE','P8S',0.8,100,95,110,:at,'test',:at) RETURNING id
                """).param("scan", scan).param("instrument", i).param("at", PERIOD.start().plusHours(10))
                .query(Long.class).single();
            if (candidate == 0) candidate = id;
        }
        jdbc.sql("""
            INSERT INTO research_scenario_run(id,instrument_id,interval,entry_price,stop_price,target_price,history_from,
                history_to,data_cutoff_at,simulation_count,horizon_bars,block_size,seed,slippage_bps,round_trip_cost_bps,
                methodology,regime,status,created_at)
            VALUES (:id,1,'ONE_MINUTE',100,95,110,:from,:to,:to,1,1,1,1,0,0,'TEST','UNKNOWN','SUCCEEDED',:at)
            """).param("id", scenario).param("from", PERIOD.start().minusDays(2)).param("to", PERIOD.start().minusDays(1))
            .param("at", PERIOD.start().plusHours(10)).update();
        for (String label : List.of("FAVORABLE", "MIXED")) {
            jdbc.sql("""
                INSERT INTO research_evaluation(id,candidate_id,scenario_run_id,decision_label,created_at)
                VALUES (:id,:candidate,:scenario,:label,:at)
                """).param("id", UUID.randomUUID()).param("candidate", candidate).param("scenario", scenario)
                .param("label", label).param("at", PERIOD.start().plusHours(11)).update();
        }
    }
}
