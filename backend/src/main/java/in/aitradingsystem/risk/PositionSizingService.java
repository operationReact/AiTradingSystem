package in.aitradingsystem.risk;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.paper.PaperExecutionModel;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PositionSizingService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public PositionSizeSuggestion suggest(
            PositionSizeRequest request, RiskPolicySnapshot policy, PortfolioRiskState state) {
        if (request == null || policy == null || state == null) {
            throw new IllegalArgumentException("request, policy and portfolio state are required");
        }
        validateSetup(request);

        BigDecimal effectiveEntry = PaperExecutionModel.fillPrice(Side.BUY, request.entryPrice(), policy.slippageRate());
        BigDecimal riskPerShare = effectiveEntry.subtract(request.stopLoss());
        if (riskPerShare.signum() <= 0) {
            throw new IllegalArgumentException("stopLoss must be below the effective BUY entry price");
        }
        if (request.targetPrice().compareTo(effectiveEntry) <= 0) {
            throw new IllegalArgumentException("targetPrice must be above the effective BUY entry price");
        }

        BigDecimal riskBudgetAmount = PaperExecutionModel.money(
                state.capitalOrEquityBase().multiply(policy.riskPerTradePercent()).divide(HUNDRED));
        BigDecimal estimatedAllInPerShare = effectiveEntry.multiply(BigDecimal.ONE.add(policy.feeRate()));
        BigDecimal grossLimit = state.capitalOrEquityBase()
                .multiply(policy.maxGrossExposurePercent()).divide(HUNDRED);
        BigDecimal symbolLimit = state.capitalOrEquityBase()
                .multiply(policy.maxSymbolExposurePercent()).divide(HUNDRED);
        BigDecimal remainingGrossExposure = grossLimit.subtract(state.currentGrossExposure()).max(BigDecimal.ZERO);
        BigDecimal remainingSymbolExposure = symbolLimit.subtract(state.currentSymbolExposure()).max(BigDecimal.ZERO);

        LinkedHashMap<RiskRuleCode, Integer> caps = new LinkedHashMap<>();
        caps.put(RiskRuleCode.PER_TRADE_RISK_BUDGET, floorQuantity(riskBudgetAmount, riskPerShare));
        caps.put(RiskRuleCode.MAX_ORDER_NOTIONAL, floorQuantity(policy.maxOrderNotional(), effectiveEntry));
        caps.put(RiskRuleCode.CASH_AVAILABILITY, floorQuantity(state.availableCash(), estimatedAllInPerShare));
        caps.put(RiskRuleCode.MAX_GROSS_EXPOSURE, floorQuantity(remainingGrossExposure, effectiveEntry));
        caps.put(RiskRuleCode.MAX_SYMBOL_EXPOSURE, floorQuantity(remainingSymbolExposure, effectiveEntry));

        RiskRuleCode limitingRule = null;
        int rawQuantity = Integer.MAX_VALUE;
        for (Map.Entry<RiskRuleCode, Integer> cap : caps.entrySet()) {
            if (cap.getValue() < rawQuantity) {
                rawQuantity = cap.getValue();
                limitingRule = cap.getKey();
            }
        }
        int quantity = floorToLot(rawQuantity, state.lotSize());
        if (quantity == Integer.MAX_VALUE) quantity = 0;

        BigDecimal estimatedNotional = PaperExecutionModel.money(effectiveEntry.multiply(BigDecimal.valueOf(quantity)));
        BigDecimal estimatedFee = PaperExecutionModel.fee(estimatedNotional, policy.feeRate());
        BigDecimal estimatedRisk = PaperExecutionModel.money(
                riskPerShare.multiply(BigDecimal.valueOf(quantity)).add(estimatedFee));

        return new PositionSizeSuggestion(
                quantity,
                riskPerShare,
                riskBudgetAmount,
                estimatedRisk,
                estimatedNotional,
                estimatedFee,
                limitingRule,
                caps,
                policy.version());
    }

    private static void validateSetup(PositionSizeRequest request) {
        if (request.instrumentId() < 1) throw new IllegalArgumentException("instrumentId must be positive");
        if (request.entryPrice() == null || request.stopLoss() == null || request.targetPrice() == null) {
            throw new IllegalArgumentException("entryPrice, stopLoss and targetPrice are required");
        }
        if (request.entryPrice().signum() <= 0 || request.stopLoss().signum() <= 0 || request.targetPrice().signum() <= 0) {
            throw new IllegalArgumentException("price levels must be positive");
        }
        if (request.stopLoss().compareTo(request.entryPrice()) >= 0
                || request.entryPrice().compareTo(request.targetPrice()) >= 0) {
            throw new IllegalArgumentException("BUY price levels must satisfy stopLoss < entryPrice < targetPrice");
        }
    }

    private static int floorQuantity(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || numerator.signum() <= 0 || denominator.signum() <= 0) return 0;
        BigDecimal value = numerator.divideToIntegralValue(denominator);
        if (value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) >= 0) return Integer.MAX_VALUE;
        return value.intValue();
    }

    private static int floorToLot(int quantity, int lotSize) {
        if (quantity <= 0) return 0;
        if (quantity == Integer.MAX_VALUE) return quantity;
        return quantity - quantity % lotSize;
    }
}
