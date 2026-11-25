package in.aitradingsystem.backtest;

import java.math.BigDecimal;
import java.time.Instant;

public record BacktestRunAnalytics(
        Integer strategyConfigVersion,
        String methodologyVersion,
        Instant dataCutoffAt,
        int sourceCandleCount,
        BigDecimal benchmarkReturnPercent,
        BigDecimal expectancyR,
        BigDecimal averageR,
        BigDecimal exposurePercent,
        BigDecimal turnover,
        int maxConsecutiveLosses,
        String assumptionsJson) {

    public BacktestRunAnalytics {
        if (methodologyVersion == null || methodologyVersion.isBlank()) {
            throw new IllegalArgumentException("methodologyVersion is required");
        }
        if (sourceCandleCount < 0) throw new IllegalArgumentException("sourceCandleCount cannot be negative");
        if (maxConsecutiveLosses < 0) throw new IllegalArgumentException("maxConsecutiveLosses cannot be negative");
        assumptionsJson = assumptionsJson == null || assumptionsJson.isBlank() ? "{}" : assumptionsJson;
    }
}
