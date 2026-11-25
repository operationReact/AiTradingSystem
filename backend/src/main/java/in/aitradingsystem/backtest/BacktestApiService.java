package in.aitradingsystem.backtest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/** Read/query/export facade for the Backtest Studio API surface. */
@Service
public class BacktestApiService {
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final BacktestRepository backtests;
    private final BacktestAnalyticsRepository analytics;

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
            instant(rs.getObject("created_at", OffsetDateTime.class)));

    public BacktestApiService(JdbcTemplate jdbc, BacktestRepository backtests, BacktestAnalyticsRepository analytics) {
        this.jdbc = jdbc;
        this.backtests = backtests;
        this.analytics = analytics;
    }

    public BacktestRunPage search(Long instrumentId, String strategy, int page, int size) {
        if (instrumentId != null && instrumentId < 1) {
            throw new BacktestException("INVALID_INSTRUMENT_ID", "Instrument ID must be positive");
        }
        if (page < 0) throw new BacktestException("INVALID_PAGE", "page must be non-negative");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BacktestException("INVALID_PAGE_SIZE", "size must be between 1 and 100");
        }
        String normalizedStrategy = strategy == null || strategy.isBlank() ? null : strategy.trim();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (instrumentId != null) {
            where.append(" AND instrument_id = ?");
            args.add(instrumentId);
        }
        if (normalizedStrategy != null) {
            where.append(" AND strategy_version = ?");
            args.add(normalizedStrategy);
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM paper_backtest_run" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<BacktestRunSummary> items = jdbc.query(
                "SELECT * FROM paper_backtest_run" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                runMapper, pageArgs.toArray());
        long totalElements = total == null ? 0L : total;
        int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new BacktestRunPage(items, page, size, totalElements, totalPages);
    }

    public List<BacktestEquityPoint> equity(UUID id) {
        backtests.requireRun(id);
        return analytics.equityCurve(id);
    }

    public String exportCsv(UUID id) {
        PaperBacktestDetail detail = backtests.detail(id);
        StringBuilder csv = new StringBuilder();
        csv.append("runId,tradeNumber,entryAt,exitAt,entryPrice,exitPrice,quantity,stopLoss,targetPrice,grossPnl,entryFee,exitFee,netPnl,exitReason\n");
        for (BacktestTradeResult trade : detail.trades()) {
            csv.append(detail.run().id()).append(',')
                    .append(trade.tradeNumber()).append(',')
                    .append(trade.entryAt()).append(',')
                    .append(trade.exitAt()).append(',')
                    .append(trade.entryPrice()).append(',')
                    .append(trade.exitPrice()).append(',')
                    .append(trade.quantity()).append(',')
                    .append(trade.stopLoss()).append(',')
                    .append(trade.targetPrice()).append(',')
                    .append(trade.grossPnl()).append(',')
                    .append(trade.entryFee()).append(',')
                    .append(trade.exitFee()).append(',')
                    .append(trade.netPnl()).append(',')
                    .append(safeText(trade.exitReason())).append('\n');
        }
        return csv.toString();
    }

    static String safeText(String value) {
        if (value == null) return "";
        String sanitized = value;
        if (!sanitized.isEmpty()) {
            char first = sanitized.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
                sanitized = "'" + sanitized;
            }
        }
        boolean quote = sanitized.indexOf(',') >= 0 || sanitized.indexOf('"') >= 0
                || sanitized.indexOf('\n') >= 0 || sanitized.indexOf('\r') >= 0;
        sanitized = sanitized.replace("\"", "\"\"");
        return quote ? "\"" + sanitized + "\"" : sanitized;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
