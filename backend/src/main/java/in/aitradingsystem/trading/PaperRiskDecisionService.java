package in.aitradingsystem.trading;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class PaperRiskDecisionService {
    private final TradingSafetyPolicy safetyPolicy;

    public PaperRiskDecisionService(TradingSafetyPolicy safetyPolicy) {
        this.safetyPolicy = safetyPolicy;
    }

    public RiskDecision evaluate(RiskEvaluationRequest request) {
        if (request == null) return RiskDecision.reject("Trade details are required.");
        if (!safetyPolicy.paperExecutionAllowed(request.tradingMode())) {
            return RiskDecision.reject("Live trading is not available yet. Switch to PAPER mode.");
        }
        if (request.emergencyStopActive()) {
            return RiskDecision.reject("Trading is paused by your safety controls.");
        }
        if (request.quantity() <= 0) return RiskDecision.reject("Enter a valid quantity.");
        if (!positive(request.entryPrice())) return RiskDecision.reject("A valid entry price is required.");
        if (!positive(request.stopLoss())) return RiskDecision.reject("Add a valid stop loss before placing the trade.");
        if (!positive(request.target())) return RiskDecision.reject("Add a valid target before placing the trade.");
        if (!positive(request.availableCapital())) return RiskDecision.reject("No PAPER buying power is available.");
        if (!positive(request.maxRiskAmount())) return RiskDecision.reject("Your risk budget must be greater than zero.");

        var notional = request.entryPrice().multiply(BigDecimal.valueOf(request.quantity()));
        if (notional.compareTo(request.availableCapital()) > 0) {
            return RiskDecision.reject("This trade is larger than your available PAPER buying power.");
        }

        var riskPerShare = request.entryPrice().subtract(request.stopLoss()).abs();
        if (riskPerShare.signum() == 0) {
            return RiskDecision.reject("Stop loss must be different from the entry price.");
        }
        var totalRisk = riskPerShare.multiply(BigDecimal.valueOf(request.quantity()));
        if (totalRisk.compareTo(request.maxRiskAmount()) > 0) {
            return RiskDecision.reject("This trade exceeds your configured risk limit.");
        }

        var rewardPerShare = request.target().subtract(request.entryPrice()).abs();
        if (rewardPerShare.compareTo(riskPerShare) < 0) {
            return RiskDecision.reject("Increase the potential reward or reduce the risk before placing this trade.");
        }
        return RiskDecision.accept();
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
