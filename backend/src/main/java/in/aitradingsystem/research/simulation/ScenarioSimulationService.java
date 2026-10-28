package in.aitradingsystem.research.simulation;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ScenarioSimulationService {
    private static final int MAX_HISTORY_CANDLES = 20_000;
    private final MarketDataRepository marketData;
    private final HistoricalScenarioSimulator simulator = new HistoricalScenarioSimulator();

    public ScenarioSimulationService(MarketDataRepository marketData) {
        this.marketData = marketData;
    }

    public ScenarioSimulationResult simulate(ScenarioSimulationRequest request) {
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
        return simulator.simulate(request, history);
    }
}
