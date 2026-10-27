package in.aitradingsystem.backtest;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BacktestRepository {
    private final JdbcTemplate jdbc;
    private final JdbcClient client;

    private final RowMapper<BacktestRunSummary> runMapper = (rs, row) -> new BacktestRunSummary(
        rs.getObject("id", UUID.class),
        rs.getLong("instrument_id"),
        rs.getString("exchange"),
        rs.getString("trading_symbol"),
        rs.getString("symbol_token"),
        rs.getObject("catalogue_version", UUID.class),
        rs.getString("interval"),
        rs.getObject("from_date", LocalDate.class),
        rs.getObject("to_date", LocalDate.class),
        rs.getInt("fast_period"),
        rs.getInt("slow_period"),
        rs.getString("strategy_version"),
        rs.getBigDecimal("initial_capital"),
        rs.getBigDecimal("risk_per_trade_percent"),
        rs.getBigDecimal("fee_rate"),
        rs.getBigDecimal("slippage_rate"),
        rs.getBigDecimal("stop_buffer_percent"),
        rs.getBigDecimal("target_risk_reward"),
        rs.getInt("candles_evaluated"),
        rs.getInt("trade_count"),
        rs.getInt("winning_trades"),
        rs.getInt("losing_trades"),
        rs.getBigDecimal("win_rate_percent"),
        rs.getBigDecimal("gross_pnl"),
        rs.getBigDecimal("total_fees"),
        rs.getBigDecimal("net_pnl"),
        rs.getBigDecimal("final_equity"),
        rs.getBigDecimal("total_return_percent"),
        rs.getBigDecimal("max_drawdown_percent"),
        rs.getBigDecimal("profit_factor"),
        instant(rs.getObject("created_at", OffsetDateTime.class))
    );

    private final RowMapper<BacktestTradeResult> tradeMapper = (rs, row) -> new BacktestTradeResult(
        rs.getInt("trade_number"),
        instant(rs.getObject("entry_at", OffsetDateTime.class)),
        instant(rs.getObject("exit_at", OffsetDateTime.class)),
        rs.getBigDecimal("entry_price"),
        rs.getBigDecimal("exit_price"),
        rs.getInt("quantity"),
        rs.getBigDecimal("stop_loss"),
        rs.getBigDecimal("target_price"),
        rs.getBigDecimal("gross_pnl"),
        rs.getBigDecimal("entry_fee"),
        rs.getBigDecimal("exit_fee"),
        rs.getBigDecimal("net_pnl"),
        rs.getString("exit_reason")
    );

    public BacktestRepository(JdbcTemplate jdbc, JdbcClient client) {
        this.jdbc = jdbc;
        this.client = client;
    }

    public void save(BacktestRunSummary run, List<BacktestTradeResult> trades) {
        client.sql("""
            INSERT INTO paper_backtest_run (
                id, instrument_id, exchange, trading_symbol, symbol_token, catalogue_version,
                interval, from_date, to_date, fast_period, slow_period, strategy_version,
                initial_capital, risk_per_trade_percent, fee_rate, slippage_rate,
                stop_buffer_percent, target_risk_reward, candles_evaluated, trade_count,
                winning_trades, losing_trades, win_rate_percent, gross_pnl, total_fees,
                net_pnl, final_equity, total_return_percent, max_drawdown_percent, profit_factor,
                status, created_at
            ) VALUES (
                :id, :instrumentId, :exchange, :symbol, :token, :catalogueVersion,
                :interval, :fromDate, :toDate, :fast, :slow, :strategyVersion,
                :capital, :riskPercent, :feeRate, :slippageRate,
                :stopBuffer, :riskReward, :candles, :tradeCount,
                :wins, :losses, :winRate, :grossPnl, :fees,
                :netPnl, :finalEquity, :totalReturn, :maxDrawdown, :profitFactor,
                'SUCCEEDED', :createdAt
            )
            """)
            .param("id", run.id())
            .param("instrumentId", run.instrumentId())
            .param("exchange", run.exchange())
            .param("symbol", run.tradingSymbol())
            .param("token", run.symbolToken())
            .param("catalogueVersion", run.catalogueVersion(), Types.OTHER)
            .param("interval", run.interval())
            .param("fromDate", run.fromDate())
            .param("toDate", run.toDate())
            .param("fast", run.fastPeriod())
            .param("slow", run.slowPeriod())
            .param("strategyVersion", run.strategyVersion())
            .param("capital", run.initialCapital())
            .param("riskPercent", run.riskPerTradePercent())
            .param("feeRate", run.feeRate())
            .param("slippageRate", run.slippageRate())
            .param("stopBuffer", run.stopBufferPercent())
            .param("riskReward", run.targetRiskReward())
            .param("candles", run.candlesEvaluated())
            .param("tradeCount", run.tradeCount())
            .param("wins", run.winningTrades())
            .param("losses", run.losingTrades())
            .param("winRate", run.winRatePercent())
            .param("grossPnl", run.grossPnl())
            .param("fees", run.totalFees())
            .param("netPnl", run.netPnl())
            .param("finalEquity", run.finalEquity())
            .param("totalReturn", run.totalReturnPercent())
            .param("maxDrawdown", run.maxDrawdownPercent())
            .param("profitFactor", run.profitFactor(), Types.NUMERIC)
            .param("createdAt", timestamp(run.createdAt()))
            .update();

        if (!trades.isEmpty()) {
            jdbc.batchUpdate("""
                INSERT INTO paper_backtest_trade (
                    run_id, trade_number, entry_at, exit_at, entry_price, exit_price, quantity,
                    stop_loss, target_price, gross_pnl, entry_fee, exit_fee, net_pnl, exit_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, trades, 500, (ps, trade) -> {
                    ps.setObject(1, run.id());
                    ps.setInt(2, trade.tradeNumber());
                    ps.setObject(3, timestamp(trade.entryAt()));
                    ps.setObject(4, timestamp(trade.exitAt()));
                    ps.setBigDecimal(5, trade.entryPrice());
                    ps.setBigDecimal(6, trade.exitPrice());
                    ps.setInt(7, trade.quantity());
                    ps.setBigDecimal(8, trade.stopLoss());
                    ps.setBigDecimal(9, trade.targetPrice());
                    ps.setBigDecimal(10, trade.grossPnl());
                    ps.setBigDecimal(11, trade.entryFee());
                    ps.setBigDecimal(12, trade.exitFee());
                    ps.setBigDecimal(13, trade.netPnl());
                    ps.setString(14, trade.exitReason());
                });
        }
    }

    public BacktestRunSummary requireRun(UUID id) {
        return client.sql("SELECT * FROM paper_backtest_run WHERE id = :id")
            .param("id", id)
            .query(runMapper)
            .optional()
            .orElseThrow(() -> new BacktestException("BACKTEST_NOT_FOUND", "Backtest run was not found"));
    }

    public PaperBacktestDetail detail(UUID id) {
        BacktestRunSummary run = requireRun(id);
        List<BacktestTradeResult> trades = jdbc.query("""
            SELECT * FROM paper_backtest_trade WHERE run_id = ?
            ORDER BY trade_number ASC
            """, tradeMapper, id);
        return new PaperBacktestDetail(run, trades);
    }

    public List<BacktestRunSummary> recent(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return jdbc.query("""
            SELECT * FROM paper_backtest_run
             ORDER BY created_at DESC, id DESC
             LIMIT ?
            """, runMapper, limit);
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}