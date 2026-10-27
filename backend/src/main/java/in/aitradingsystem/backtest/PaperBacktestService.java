package in.aitradingsystem.backtest;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.config.RiskProperties;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
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

/** Runs a reproducible, offline simulation over already-persisted candles; never submits broker orders. */
@Service
public class PaperBacktestService {
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

    public PaperBacktestService(
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
    public PaperBacktestDetail run(PaperBacktestRequest request) {
        validateRequest(request);
        Instrument instrument;
        try {
            instrument = instruments.requireActiveInstrument(request.instrumentId());
        } catch (InstrumentMasterException ex) {
            throw ex;
        }

        LocalDate from = request.fromDate();
        LocalDate to = request.toDate();
        Instant fromInclusive = from.atStartOfDay(MARKET_ZONE).toInstant();
        Instant toExclusive = to.plusDays(1).atStartOfDay(MARKET_ZONE).toInstant();
        List<MarketCandle> candles = marketData.backtestCandles(
            instrument.id(), request.interval().name(), fromInclusive, toExclusive, MAX_CANDLES + 1);
        if (candles.size() > MAX_CANDLES) {
            throw new BacktestException("TOO_MANY_CANDLES",
                "This backtest window contains more than 20,000 candles; choose a smaller date range or a larger interval");
        }
        if (candles.size() < slowPeriod + 2) {
            throw new BacktestException("INSUFFICIENT_HISTORY",
                "At least " + (slowPeriod + 2) + " stored candles are required for the configured strategy");
        }

        BigDecimal capital = request.initialCapital() == null ? risk.defaultCapital() : request.initialCapital();
        SmaBacktestEngine.Config config = new SmaBacktestEngine.Config(
            capital, fastPeriod, slowPeriod, risk.riskPerTradePercent(), risk.feeRate(),
            risk.slippageRate(), stopBufferPercent, targetRiskReward);
        BacktestEngineResult result = engine.run(candles, config);
        Instant now = clock.instant();

        BacktestRunSummary summary = new BacktestRunSummary(
            UUID.randomUUID(), instrument.id(), instrument.exchange(), instrument.tradingSymbol(),
            instrument.symbolToken(), instrument.catalogueVersion(), request.interval().name(),
            from, to, fastPeriod, slowPeriod, SmaBacktestEngine.STRATEGY_VERSION,
            capital, risk.riskPerTradePercent(), risk.feeRate(), risk.slippageRate(),
            stopBufferPercent, targetRiskReward, result.candlesEvaluated(), result.tradeCount(),
            result.winningTrades(), result.losingTrades(), result.winRatePercent(), result.grossPnl(),
            result.totalFees(), result.netPnl(), result.finalEquity(), result.totalReturnPercent(),
            result.maxDrawdownPercent(), result.profitFactor(), now);

        backtests.save(summary, result.trades());
        audit.record("PAPER_BACKTEST_COMPLETED", "PAPER_BACKTEST", summary.id().toString(), Map.of(
            "instrumentId", instrument.id(),
            "symbol", instrument.tradingSymbol(),
            "interval", request.interval().name(),
            "fromDate", from.toString(),
            "toDate", to.toString(),
            "candlesEvaluated", result.candlesEvaluated(),
            "tradeCount", result.tradeCount(),
            "netPnl", result.netPnl(),
            "totalReturnPercent", result.totalReturnPercent(),
            "maxDrawdownPercent", result.maxDrawdownPercent()
        ));
        return new PaperBacktestDetail(summary, result.trades());
    }

    public PaperBacktestDetail get(UUID id) {
        return backtests.detail(id);
    }

    public List<BacktestRunSummary> recent(int limit) {
        if (limit < 1 || limit > 100) {
            throw new BacktestException("INVALID_LIMIT", "Limit must be between 1 and 100");
        }
        return backtests.recent(limit);
    }

    private void validateRequest(PaperBacktestRequest request) {
        if (request == null) throw new BacktestException("INVALID_REQUEST", "Request body is required");
        if (request.instrumentId() < 1) {
            throw new BacktestException("INVALID_INSTRUMENT_ID", "Instrument ID must be positive");
        }
        if (request.interval() == null) {
            throw new BacktestException("INVALID_INTERVAL", "A supported candle interval is required");
        }
        if (request.fromDate() == null || request.toDate() == null) {
            throw new BacktestException("INVALID_DATE_RANGE", "Both fromDate and toDate are required");
        }
        if (request.fromDate().isAfter(request.toDate())) {
            throw new BacktestException("INVALID_DATE_RANGE", "fromDate must be on or before toDate");
        }
        if (request.toDate().isAfter(LocalDate.now(clock.withZone(MARKET_ZONE)))) {
            throw new BacktestException("FUTURE_DATE_NOT_ALLOWED", "Backtest cannot use future market-data dates");
        }
        long days = ChronoUnit.DAYS.between(request.fromDate(), request.toDate()) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new BacktestException("DATE_RANGE_TOO_LARGE", "Backtest window is limited to " + MAX_RANGE_DAYS + " calendar days");
        }
        if (request.initialCapital() != null
                && (request.initialCapital().compareTo(new BigDecimal("10000")) < 0
                    || request.initialCapital().compareTo(new BigDecimal("100000000")) > 0)) {
            throw new BacktestException("INVALID_INITIAL_CAPITAL", "Initial capital must be between 10000 and 100000000");
        }
    }
}