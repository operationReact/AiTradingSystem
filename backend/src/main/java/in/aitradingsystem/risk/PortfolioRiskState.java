package in.aitradingsystem.risk;

import java.math.BigDecimal;

/** Server-owned portfolio inputs used by the advisory position-size calculator. */
public record PortfolioRiskState(
        BigDecimal capitalOrEquityBase,
        BigDecimal availableCash,
        BigDecimal currentGrossExposure,
        BigDecimal currentSymbolExposure,
        int lotSize) {

    public PortfolioRiskState {
        if (capitalOrEquityBase == null || capitalOrEquityBase.signum() <= 0) {
            throw new IllegalArgumentException("capitalOrEquityBase must be positive");
        }
        if (availableCash == null || currentGrossExposure == null || currentSymbolExposure == null) {
            throw new IllegalArgumentException("portfolio monetary state is required");
        }
        if (availableCash.signum() < 0 || currentGrossExposure.signum() < 0 || currentSymbolExposure.signum() < 0) {
            throw new IllegalArgumentException("portfolio monetary state cannot be negative");
        }
        if (lotSize < 1) throw new IllegalArgumentException("lotSize must be positive");
    }
}
