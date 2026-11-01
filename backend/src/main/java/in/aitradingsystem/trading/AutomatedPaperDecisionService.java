package in.aitradingsystem.trading;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class AutomatedPaperDecisionService {
    private static final int MIN_CONFIDENCE = 70;
    private final PaperTradeIntentService intentService;

    public AutomatedPaperDecisionService(PaperTradeIntentService intentService) {
        this.intentService = intentService;
    }

    public PaperIntentDecision evaluate(AutomatedPaperOpportunity opportunity, BigDecimal availableCapital,
                                        BigDecimal maxRiskAmount, boolean emergencyStopActive) {
        if (opportunity == null || !opportunity.eligible()) {
            return new PaperIntentDecision(false, null, "This AI opportunity is not eligible for automated trading.");
        }
        if (opportunity.confidencePercent() < MIN_CONFIDENCE) {
            return new PaperIntentDecision(false, opportunity.opportunityId(), "AI confidence is below your automation threshold.");
        }
        var intent = new PaperTradeIntent(
            "ai:" + opportunity.opportunityId(), opportunity.symbol(), opportunity.side(), opportunity.quantity(),
            opportunity.entryPrice(), opportunity.stopLoss(), opportunity.target(), TradeIntentSource.AI_AUTOMATION);
        return intentService.evaluate(intent, availableCapital, maxRiskAmount, emergencyStopActive);
    }
}
