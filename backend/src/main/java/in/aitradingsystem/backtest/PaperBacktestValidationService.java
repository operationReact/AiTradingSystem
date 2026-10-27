package in.aitradingsystem.backtest;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.config.RiskProperties;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Walk-forward-style holdout validation for fixed strategy parameters. Training and holdout runs
 * are independently initialized with the same capital; holdout entries are disabled before the
 * split boundary while pre-split candles are retained only for indicator warm-up.
 */
@Service
public class PaperBacktestValidationService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int MAX_RANGE_DAYS = 366;
    private static final int MAX_CANDLES = 20_000;
    private final InstrumentMasterService instruments;
    private final MarketDataRepository marketData;
    private final BacktestRepository backtests;
    private final SmaBacktestEngine engine;
    private final RiskProperties risk;
    private final AuditService audit;
    private final Clock clock;
    private final int fastPeriod;
    private final int slowPeriod;
    private final BigDecimal targetRiskReward;
    private final BigDecimal stopBufferPercent;

    public PaperBacktestValidationService(
        InstrumentMasterService instruments,
        MarketDataRepository marketData,
        BacktestRepository backtests,
        SmaBacktestEngine engine,
        RiskProperties risk,
        AuditService audit,
        Clock clock,
        @Value("${app.strategy.fast-period:5}") int fastPeriod,
        @Value("${app.strategy.slow-period:20}") int slowPeriod,
        @Value("${app.strategy.target-risk-reward:2}") BigDecimal targetRiskReward,
        @Value("${app.strategy.stop-buffer-percent:0.5}") BigDecimal stopBufferPercent
    ) {
        this.instruments = instruments;
        this.marketData = marketData;
        this.backtests = backtests;
        this.engine = engine;
        this.risk = risk;
        this.audit = audit;
        this.clock = clock;
        if (fastPeriod < 2 || slowPeriod <= fastPeriod || slowPeriod > 500) {
            throw new IllegalArgumentException("Strategy periods must satisfy 2 <= fastPeriod < slowPeriod <= 500");
        }
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
        this.targetRiskReward = targetRiskReward;
        this.stopBufferPercent = stopBufferPercent;
    }

    @Transactional
    public PaperBacktestValidationDetail run(PaperBacktestValidationRequest request) {
        validate(request);
        Instrument instrument = instruments.requireActiveInstrument(request.instrumentId());
        int holdoutPercent = request.outOfSamplePercent() == null ? 30 : request.outOfSamplePercent();
        long totalDays = ChronoUnit.DAYS.between(request.fromDate(), request.toDate()) + 1;
        long holdoutDays = Math.max(1, (long) Math.ceil(totalDays * holdoutPercent / 100.0d));
        if (holdoutDays >= totalDays) {
            throw new BacktestException("INVALID_SPLIT", "Date range is too short for the selected holdout percentage");
        }
        LocalDate splitDate = request.toDate().minusDays(holdoutDays).plusDays(1);
        Instant fromInclusive = request.fromDate().atStartOfDay(MARKET_ZONE).toInstant();
        Instant splitInstant = splitDate.atStartOfDay(MARKET_ZONE).toInstant();
        Instant toExclusive = request.toDate().plusDays(1).atStartOfDay(MARKET_ZONE).toInstant();

        List<MarketCandle> candles = marketData.backtestCandles(
            instrument.id(), request.interval().name(), fromInclusive, toExclusive, MAX_CANDLES + 1);
        if (candles.size() > MAX_CANDLES) {
            throw new BacktestException("TOO_MANY_CANDLES",
                "This validation window contains more than 20,000 candles; choose a smaller date range or a larger interval");
        }
        List<MarketCandle> trainingCandles = candles.stream()
            .filter(c -> c.candleTime().isBefore(splitInstant)).toList();
        int holdoutCandles = (int) candles.stream()
            .filter(c -> !c.candleTime().isBefore(splitInstant)).count();
        if (trainingCandles.size() < slowPeriod + 2) {
            throw new BacktestException("INSUFFICIENT_TRAINING_HISTORY",
                "The training window must contain at least " + (slowPeriod + 2) + " candles for indicator warm-up");
        }
        if (holdoutCandles < 1) {
            throw new BacktestException("INSUFFICIENT_HOLDOUT_HISTORY",
                "No stored candles exist in the selected out-of-sample window");
        }

        BigDecimal capital = request.initialCapital() == null ? risk.defaultCapital() : request.initialCapital();
        SmaBacktestEngine.Config config = new SmaBacktestEngine.Config(
            capital, fastPeriod, slowPeriod, risk.riskPerTradePercent(), risk.feeRate(),
            risk.slippageRate(), stopBufferPercent, targetRiskReward);

        BacktestEngineResult trainResult = engine.run(trainingCandles, config);
        // Use all candles for indicators, but allow entries only at/after the split timestamp.
        BacktestEngineResult holdoutResult;
        try {
            holdoutResult = engine.run(candles, config, splitInstant);
        } catch (IllegalArgumentException ex) {
            throw new BacktestException("INSUFFICIENT_HOLDOUT_HISTORY",
                "The selected window must include warm-up candles before and evaluation candles after the split");
        }

        Instant now = clock.instant();
        BacktestRunSummary trainingRun = summary(instrument, request.interval().name(),
            request.fromDate(), splitDate.minusDays(1), capital, trainResult, now);
        BacktestRunSummary holdoutRun = summary(instrument, request.interval().name(),
            splitDate, request.toDate(), capital, holdoutResult, now);
        backtests.save(trainingRun, trainResult.trades());
        backtests.save(holdoutRun, holdoutResult.trades());

        BigDecimal delta = holdoutResult.totalReturnPercent().subtract(trainResult.totalReturnPercent())
            .setScale(4, RoundingMode.HALF_UP);
        BacktestValidationSummary validation = new BacktestValidationSummary(
            UUID.randomUUID(), instrument.id(), instrument.exchange(), instrument.tradingSymbol(),
            instrument.symbolToken(), instrument.catalogueVersion(), request.interval().name(),
            request.fromDate(), request.toDate(), splitDate, holdoutPercent,
            trainingRun.id(), holdoutRun.id(), trainResult.candlesEvaluated(),
            holdoutResult.candlesEvaluated(), trainResult.totalReturnPercent(),
            holdoutResult.totalReturnPercent(), delta, now);
        backtests.saveValidation(validation);
        Map<String, Object> auditDetails = new java.util.LinkedHashMap<>();
        auditDetails.put("instrumentId", instrument.id());
        auditDetails.put("symbol", instrument.tradingSymbol());
        auditDetails.put("interval", request.interval().name());
        auditDetails.put("fromDate", request.fromDate().toString());
        auditDetails.put("splitDate", splitDate.toString());
        auditDetails.put("toDate", request.toDate().toString());
        auditDetails.put("outOfSamplePercent", holdoutPercent);
        auditDetails.put("trainingCandles", trainResult.candlesEvaluated());
        auditDetails.put("outOfSampleCandles", holdoutResult.candlesEvaluated());
        auditDetails.put("trainingReturnPercent", trainResult.totalReturnPercent());
        auditDetails.put("outOfSampleReturnPercent", holdoutResult.totalReturnPercent());
        auditDetails.put("returnDeltaPercentagePoints", delta);
        audit.record("PAPER_BACKTEST_VALIDATION_COMPLETED", "PAPER_BACKTEST_VALIDATION",
            validation.id().toString(), auditDetails);
        return new PaperBacktestValidationDetail(validation,
            new PaperBacktestDetail(trainingRun, trainResult.trades()),
            new PaperBacktestDetail(holdoutRun, holdoutResult.trades()));
    }

    public PaperBacktestValidationDetail get(UUID id) {
        BacktestValidationSummary summary = backtests.requireValidation(id);
        return new PaperBacktestValidationDetail(summary,
            backtests.detail(summary.trainingRunId()), backtests.detail(summary.outOfSampleRunId()));
    }

    public List<BacktestValidationSummary> recent(int limit) {
        return backtests.recentValidations(limit);
    }

    private BacktestRunSummary summary(Instrument instrument, String interval, LocalDate from, LocalDate to,
                                       BigDecimal capital, BacktestEngineResult result, Instant createdAt) {
        return new BacktestRunSummary(
            UUID.randomUUID(), instrument.id(), instrument.exchange(), instrument.tradingSymbol(),
            instrument.symbolToken(), instrument.catalogueVersion(), interval, from, to,
            fastPeriod, slowPeriod, SmaBacktestEngine.STRATEGY_VERSION, capital,
            risk.riskPerTradePercent(), risk.feeRate(), risk.slippageRate(),
            stopBufferPercent, targetRiskReward, result.candlesEvaluated(), result.tradeCount(),
            result.winningTrades(), result.losingTrades(), result.winRatePercent(), result.grossPnl(),
            result.totalFees(), result.netPnl(), result.finalEquity(), result.totalReturnPercent(),
            result.maxDrawdownPercent(), result.profitFactor(), createdAt);
    }

    private void validate(PaperBacktestValidationRequest request) {
        if (request == null) throw new BacktestException("INVALID_REQUEST", "Request body is required");
        if (request.instrumentId() < 1) throw new BacktestException("INVALID_INSTRUMENT_ID", "Instrument ID must be positive");
        if (request.interval() == null) throw new BacktestException("INVALID_INTERVAL", "A supported candle interval is required");
        if (request.fromDate() == null || request.toDate() == null) {
            throw new BacktestException("INVALID_DATE_RANGE", "Both fromDate and toDate are required");
        }
        if (request.fromDate().isAfter(request.toDate())) {
            throw new BacktestException("INVALID_DATE_RANGE", "fromDate must be on or before toDate");
        }
        if (request.toDate().isAfter(LocalDate.now(clock.withZone(MARKET_ZONE)))) {
            throw new BacktestException("FUTURE_DATE_NOT_ALLOWED", "Validation cannot use future market-data dates");
        }
        long days = ChronoUnit.DAYS.between(request.fromDate(), request.toDate()) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new BacktestException("DATE_RANGE_TOO_LARGE", "Validation window is limited to " + MAX_RANGE_DAYS + " calendar days");
        }
        int percent = request.outOfSamplePercent() == null ? 30 : request.outOfSamplePercent();
        if (percent < 10 || percent > 50) {
            throw new BacktestException("INVALID_SPLIT", "outOfSamplePercent must be between 10 and 50");
        }
        if (request.initialCapital() != null
                && (request.initialCapital().compareTo(new BigDecimal("10000")) < 0
                    || request.initialCapital().compareTo(new BigDecimal("100000000")) > 0)) {
            throw new BacktestException("INVALID_INITIAL_CAPITAL", "Initial capital must be between 10000 and 100000000");
        }
    }
}
