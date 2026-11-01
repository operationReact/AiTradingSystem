package in.aitradingsystem.trading;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Shared entry boundary for manual and AI-automated PAPER trade intents.
 * Persistence/order/fill integration is deliberately the next slice; this
 * boundary establishes risk and idempotency before either source can proceed.
 */
@Service
public class PaperTradeIntentService {
    private final PaperRiskDecisionService riskService;
    private final Set<String> acceptedKeys = ConcurrentHashMap.newKeySet();

    public PaperTradeIntentService(PaperRiskDecisionService riskService) {
        this.riskService = riskService;
    }

    public PaperIntentDecision evaluate(PaperTradeIntent intent, BigDecimal availableCapital,
                                        BigDecimal maxRiskAmount, boolean emergencyStopActive) {
        if (intent == null || blank(intent.idempotencyKey())) {
            return new PaperIntentDecision(false, null, "Unable to identify this trade request. Please try again.");
        }
        if (acceptedKeys.contains(intent.idempotencyKey())) {
            return new PaperIntentDecision(false, intent.idempotencyKey(), "This trade request was already accepted.");
        }

        var executionMode = intent.source() == TradeIntentSource.AI_AUTOMATION
            ? ExecutionMode.AUTOMATED : ExecutionMode.MANUAL;
        var risk = riskService.evaluate(new RiskEvaluationRequest(
            TradingMode.PAPER, executionMode, intent.quantity(), intent.entryPrice(), intent.stopLoss(), intent.target(),
            availableCapital, maxRiskAmount, emergencyStopActive));
        if (!risk.accepted()) {
            return new PaperIntentDecision(false, intent.idempotencyKey(), risk.customerMessage());
        }
        if (!acceptedKeys.add(intent.idempotencyKey())) {
            return new PaperIntentDecision(false, intent.idempotencyKey(), "This trade request was already accepted.");
        }
        return new PaperIntentDecision(true, intent.idempotencyKey(), "PAPER trade passed risk checks.");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
