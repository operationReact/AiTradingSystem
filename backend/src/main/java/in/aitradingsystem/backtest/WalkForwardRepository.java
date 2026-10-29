package in.aitradingsystem.backtest;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WalkForwardRepository {
    private final JdbcClient client;

    private final RowMapper<BacktestWalkForwardRun> runMapper = (rs, row) -> new BacktestWalkForwardRun(
            rs.getObject("id", UUID.class), rs.getLong("instrument_id"), rs.getString("exchange"),
            rs.getString("trading_symbol"), rs.getString("interval"), rs.getString("strategy_code"),
            rs.getString("strategy_version"), rs.getObject("from_date", java.time.LocalDate.class),
            rs.getObject("to_date", java.time.LocalDate.class), rs.getInt("training_window_days"),
            rs.getInt("validation_window_days"), rs.getInt("step_days"), rs.getString("status"),
            instant(rs.getObject("created_at", OffsetDateTime.class)),
            instant(rs.getObject("completed_at", OffsetDateTime.class)), rs.getString("error_message"));

    private final RowMapper<BacktestWalkForwardFold> foldMapper = (rs, row) -> new BacktestWalkForwardFold(
            rs.getObject("walk_forward_id", UUID.class), rs.getInt("fold_number"),
            rs.getObject("training_run_id", UUID.class), rs.getObject("validation_run_id", UUID.class),
            rs.getObject("training_from", java.time.LocalDate.class), rs.getObject("training_to", java.time.LocalDate.class),
            rs.getObject("validation_from", java.time.LocalDate.class), rs.getObject("validation_to", java.time.LocalDate.class),
            rs.getBigDecimal("training_return_percent"), rs.getBigDecimal("validation_return_percent"),
            rs.getBigDecimal("validation_max_drawdown_percent"));

    public WalkForwardRepository(JdbcClient client) { this.client = client; }

    public void create(BacktestWalkForwardRun run) {
        client.sql("""
            INSERT INTO backtest_walk_forward_run (
                id, instrument_id, exchange, trading_symbol, interval, strategy_code, strategy_version,
                from_date, to_date, training_window_days, validation_window_days, step_days,
                status, created_at, completed_at, error_message
            ) VALUES (
                :id, :instrumentId, :exchange, :symbol, :interval, :strategyCode, :strategyVersion,
                :fromDate, :toDate, :trainingDays, :validationDays, :stepDays,
                :status, :createdAt, :completedAt, :errorMessage
            )
            """)
            .param("id", run.id(), Types.OTHER).param("instrumentId", run.instrumentId())
            .param("exchange", run.exchange()).param("symbol", run.tradingSymbol()).param("interval", run.interval())
            .param("strategyCode", run.strategyCode()).param("strategyVersion", run.strategyVersion())
            .param("fromDate", run.fromDate()).param("toDate", run.toDate()).param("trainingDays", run.trainingWindowDays())
            .param("validationDays", run.validationWindowDays()).param("stepDays", run.stepDays())
            .param("status", run.status()).param("createdAt", timestamp(run.createdAt()))
            .param("completedAt", timestamp(run.completedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
            .param("errorMessage", run.errorMessage(), Types.VARCHAR).update();
    }

    public void saveFold(BacktestWalkForwardFold fold) {
        client.sql("""
            INSERT INTO backtest_walk_forward_fold (
                walk_forward_id, fold_number, training_run_id, validation_run_id,
                training_from, training_to, validation_from, validation_to,
                training_return_percent, validation_return_percent, validation_max_drawdown_percent
            ) VALUES (
                :id, :fold, :trainingRun, :validationRun,
                :trainingFrom, :trainingTo, :validationFrom, :validationTo,
                :trainingReturn, :validationReturn, :validationDrawdown
            )
            """)
            .param("id", fold.walkForwardId(), Types.OTHER).param("fold", fold.foldNumber())
            .param("trainingRun", fold.trainingRunId(), Types.OTHER).param("validationRun", fold.validationRunId(), Types.OTHER)
            .param("trainingFrom", fold.trainingFrom()).param("trainingTo", fold.trainingTo())
            .param("validationFrom", fold.validationFrom()).param("validationTo", fold.validationTo())
            .param("trainingReturn", fold.trainingReturnPercent()).param("validationReturn", fold.validationReturnPercent())
            .param("validationDrawdown", fold.validationMaxDrawdownPercent()).update();
    }

    public void complete(UUID id, Instant completedAt) {
        client.sql("UPDATE backtest_walk_forward_run SET status='SUCCEEDED', completed_at=:at, error_message=NULL WHERE id=:id")
                .param("at", timestamp(completedAt)).param("id", id, Types.OTHER).update();
    }

    public void fail(UUID id, Instant completedAt, String error) {
        String bounded = error == null ? "Walk-forward validation failed" : error.substring(0, Math.min(500, error.length()));
        client.sql("UPDATE backtest_walk_forward_run SET status='FAILED', completed_at=:at, error_message=:error WHERE id=:id")
                .param("at", timestamp(completedAt)).param("error", bounded).param("id", id, Types.OTHER).update();
    }

    public BacktestWalkForwardRun require(UUID id) {
        return client.sql("SELECT * FROM backtest_walk_forward_run WHERE id=:id").param("id", id, Types.OTHER)
                .query(runMapper).optional().orElseThrow(() -> new BacktestException("WALK_FORWARD_NOT_FOUND", "Walk-forward run was not found"));
    }

    public List<BacktestWalkForwardFold> folds(UUID id) {
        return client.sql("SELECT * FROM backtest_walk_forward_fold WHERE walk_forward_id=:id ORDER BY fold_number")
                .param("id", id, Types.OTHER).query(foldMapper).list();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
}
