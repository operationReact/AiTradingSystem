package in.aitradingsystem.research.simulation;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataQualityService;
import in.aitradingsystem.marketdata.MarketDataRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ScenarioSimulationService {
    private static final int MAX_HISTORY_CANDLES = 20_000;
    private final MarketDataRepository marketData;
    private final MarketDataQualityService quality;
    private final HistoricalScenarioSimulator simulator = new HistoricalScenarioSimulator();
    private final RegimeClassifier regimeClassifier = new RegimeClassifier();

    @Autowired
    public ScenarioSimulationService(MarketDataRepository marketData, MarketDataQualityService quality) {
        if (quality == null) throw new IllegalArgumentException("market data quality gate is required");
        this.marketData = marketData;
        this.quality = quality;
    }

    /** Unit-fixture constructor: production always injects the quality gate. */
    ScenarioSimulationService(MarketDataRepository marketData) {
        this.marketData = marketData;
        this.quality = null;
    }

    public ScenarioSimulationResult simulate(ScenarioSimulationRequest request) {
        List<MarketCandle> history = eligibleHistory(request);
        return simulator.simulate(request, history);
    }

    /**
     * Point-in-time regime evidence used by orchestration persistence and later UI presentation.
     * The same historical window and research quality gate as simulation are applied; no future candle is read.
     */
    public RegimeSnapshot classifyRegime(ScenarioSimulationRequest request) {
        List<MarketCandle> history = eligibleHistory(request);
        return regimeClassifier.classify(history, request.historyTo());
    }

    private List<MarketCandle> eligibleHistory(ScenarioSimulationRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        HistoricalCandleInterval interval;
        try {
            interval = HistoricalCandleInterval.valueOf(request.interval());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported candle interval");
        }
        if (interval == HistoricalCandleInterval.ONE_DAY) {
            throw new IllegalArgumentException("scenario simulation currently supports intraday intervals only");
        }
        List<MarketCandle> history = marketData.backtestCandles(
                request.instrumentId(), request.interval(), request.historyFrom(), request.historyTo(), MAX_HISTORY_CANDLES);
        if (quality != null && !quality.eligibleForResearch(quality.evaluateBars(request.instrumentId(), interval, history))) {
            throw new IllegalArgumentException("historical scenario data failed the market-data quality gate");
        }
        return history;
    }
}
