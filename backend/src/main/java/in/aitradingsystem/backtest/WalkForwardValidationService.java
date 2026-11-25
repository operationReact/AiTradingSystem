package in.aitradingsystem.backtest;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Executes deterministic rolling train/validation folds using one fixed strategy configuration.
 * V1 deliberately performs no optimization or hyperparameter search between folds.
 */
@Service
public class WalkForwardValidationService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int MAX_RANGE_DAYS = 366;
    private static final int MAX_FOLDS = 100;
    private static final String STRATEGY_CODE = "SMA_TREND_VOLUME";

    private final InstrumentMasterService instruments;
    private final PaperBacktestService paperBacktests;
    private final WalkForwardRepository repository;
    private final Clock clock;

    public WalkForwardValidationService(
            InstrumentMasterService instruments,
            PaperBacktestService paperBacktests,
            WalkForwardRepository repository,
            Clock clock) {
        this.instruments = instruments;
        this.paperBacktests = paperBacktests;
        this.repository = repository;
        this.clock = clock;
    }

    public WalkForwardDetail run(WalkForwardRequest request) {
        validate(request);
        Instrument instrument = instruments.requireActiveInstrument(request.instrumentId());
        List<WalkForwardWindowPlanner.Window> windows;
        try {
            windows = WalkForwardWindowPlanner.plan(request.fromDate(), request.toDate(),
                    request.trainingWindowDays(), request.validationWindowDays(), request.stepDays());
        } catch (IllegalArgumentException ex) {
            throw new BacktestException("INVALID_WALK_FORWARD_WINDOWS", ex.getMessage());
        }
        if (windows.size() > MAX_FOLDS) {
            throw new BacktestException("TOO_MANY_WALK_FORWARD_FOLDS", "Walk-forward validation is limited to 100 folds");
        }

        UUID id = UUID.randomUUID();
        Instant startedAt = clock.instant();
        BacktestWalkForwardRun run = new BacktestWalkForwardRun(
                id, instrument.id(), instrument.exchange(), instrument.tradingSymbol(), request.interval().name(),
                STRATEGY_CODE, SmaBacktestEngine.STRATEGY_VERSION,
                request.fromDate(), request.toDate(), request.trainingWindowDays(), request.validationWindowDays(),
                request.stepDays(), "RUNNING", startedAt, null, null);
        repository.create(run);

        try {
            for (WalkForwardWindowPlanner.Window window : windows) {
                PaperBacktestDetail training = paperBacktests.run(new PaperBacktestRequest(
                        instrument.id(), request.interval(), window.trainingFrom(), window.trainingTo(), request.initialCapital()));
                PaperBacktestDetail validation = paperBacktests.run(new PaperBacktestRequest(
                        instrument.id(), request.interval(), window.validationFrom(), window.validationTo(), request.initialCapital()));
                repository.saveFold(new BacktestWalkForwardFold(
                        id, window.foldNumber(), training.run().id(), validation.run().id(),
                        window.trainingFrom(), window.trainingTo(), window.validationFrom(), window.validationTo(),
                        training.run().totalReturnPercent(), validation.run().totalReturnPercent(),
                        validation.run().maxDrawdownPercent()));
            }
            repository.complete(id, clock.instant());
            return get(id);
        } catch (RuntimeException ex) {
            repository.fail(id, clock.instant(), ex.getMessage());
            throw ex;
        }
    }

    public WalkForwardDetail get(UUID id) {
        return new WalkForwardDetail(repository.require(id), repository.folds(id));
    }

    private void validate(WalkForwardRequest request) {
        if (request == null) throw new BacktestException("INVALID_REQUEST", "Request body is required");
        if (request.instrumentId() < 1) throw new BacktestException("INVALID_INSTRUMENT_ID", "Instrument ID must be positive");
        if (request.interval() == null) throw new BacktestException("INVALID_INTERVAL", "A supported candle interval is required");
        if (request.fromDate() == null || request.toDate() == null || request.fromDate().isAfter(request.toDate())) {
            throw new BacktestException("INVALID_DATE_RANGE", "A valid fromDate/toDate range is required");
        }
        LocalDate today = LocalDate.now(clock.withZone(MARKET_ZONE));
        if (request.toDate().isAfter(today)) {
            throw new BacktestException("FUTURE_DATE_NOT_ALLOWED", "Walk-forward validation cannot use future dates");
        }
        long days = ChronoUnit.DAYS.between(request.fromDate(), request.toDate()) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new BacktestException("DATE_RANGE_TOO_LARGE", "Walk-forward validation is limited to 366 calendar days");
        }
        if (request.trainingWindowDays() < 1 || request.validationWindowDays() < 1 || request.stepDays() < 1) {
            throw new BacktestException("INVALID_WALK_FORWARD_WINDOWS", "trainingWindowDays, validationWindowDays and stepDays must be positive");
        }
        BigDecimal capital = request.initialCapital();
        if (capital != null && (capital.compareTo(new BigDecimal("10000")) < 0
                || capital.compareTo(new BigDecimal("100000000")) > 0)) {
            throw new BacktestException("INVALID_INITIAL_CAPITAL", "Initial capital must be between 10000 and 100000000");
        }
    }
}
