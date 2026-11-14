package in.aitradingsystem.mind;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Truthful registry of external research frameworks named in the Mind UI.
 *
 * <p>No external engine is installed or wired into any Mind run. Each entry declares what it would
 * provide, its dependencies, and the exact missing adapter. The registry never reports an engine as
 * available merely because it is named; {@code connected} is always false until an adapter exists and
 * has its own runtime evidence.
 */
public final class MindResearchEngineRegistry {
    private MindResearchEngineRegistry() {}

    public record Engine(String id, String name, String category, boolean connected, boolean enabled,
                         String status, List<String> capabilities, List<String> dependencies,
                         String missing) {}

    public record Registry(String contractVersion, Instant generatedAt, int total, int connected,
                           List<Engine> engines) {}

    static final List<Engine> ENGINES = List.of(
        new Engine("VECTORBT", "VectorBT", "Vectorised backtests", false, false, "NOT_CONNECTED",
            List.of("vectorised backtest", "parameter sweep"),
            List.of("numpy", "pandas", "candle lineage from canonical dataset"),
            "No adapter, dependency install, or run evidence in this repository."),
        new Engine("BACKTESTING_PY", "Backtesting.py", "Event backtests", false, false, "NOT_CONNECTED",
            List.of("event-driven backtest"),
            List.of("backtesting package", "candle lineage from canonical dataset"),
            "No adapter, dependency install, or run evidence in this repository."),
        new Engine("NAUTILUS", "NautilusTrader", "Execution simulation", false, false, "NOT_CONNECTED",
            List.of("high-fidelity simulation"),
            List.of("nautilus_trader package", "canonical instrument definitions"),
            "No adapter, dependency install, or run evidence in this repository."),
        new Engine("LEAN", "QuantConnect LEAN", "Reference engine", false, false, "NOT_CONNECTED",
            List.of("reference backtest comparison"),
            List.of("LEAN runtime", "data license review"),
            "Reference only; no runtime, adapter or licence review exists."),
        new Engine("FREQTRADE", "Freqtrade", "Crypto-oriented bot framework", false, false, "NOT_APPLICABLE",
            List.of("none for NSE/BSE cash equities"),
            List.of("exchange connectors (out of scope for India cash equities)"),
            "Not applicable to NSE/BSE cash equities; not planned for PAPER."));

    public static Registry snapshot(Clock clock) {
        int connected = (int) ENGINES.stream().filter(Engine::connected).count();
        return new Registry("mind.research-engines.v1", clock.instant(), ENGINES.size(), connected, ENGINES);
    }
}
