package in.aitradingsystem.research.simulation;

public record ScenarioSimulationResult(
        String methodology,
        long seed,
        int simulations,
        int historicalSamples,
        int horizonBars,
        int blockSize,
        int targetFirstCount,
        int stopFirstCount,
        int neitherCount,
        double targetFirstProbability,
        double stopFirstProbability,
        double neitherProbability,
        double expectedR,
        double p05R,
        double p50R,
        double p95R) {}
