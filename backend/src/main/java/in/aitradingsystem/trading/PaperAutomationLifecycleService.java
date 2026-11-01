package in.aitradingsystem.trading;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class PaperAutomationLifecycleService {
    private final AccountPaperAutomationService automation;
    private final SimulatedPaperOrderService orders;
    private final PaperPositionService positions;

    public PaperAutomationLifecycleService(AccountPaperAutomationService automation,
                                           SimulatedPaperOrderService orders,
                                           PaperPositionService positions) {
        this.automation = automation;
        this.orders = orders;
        this.positions = positions;
    }

    public PaperPosition enter(String accountId, AutomatedPaperOpportunity opportunity,
                               BigDecimal availableCapital, BigDecimal maxRiskAmount,
                               boolean emergencyStopActive) {
        var decision = automation.evaluate(accountId, opportunity, availableCapital, maxRiskAmount, emergencyStopActive);
        if (!decision.accepted()) throw new IllegalStateException(decision.customerMessage());
        var intent = new PaperTradeIntent("ai:" + opportunity.opportunityId(), opportunity.symbol(), opportunity.side(),
            opportunity.quantity(), opportunity.entryPrice(), opportunity.stopLoss(), opportunity.target(),
            TradeIntentSource.AI_AUTOMATION);
        return positions.open(orders.fill(intent));
    }

    public PaperPosition onPrice(PaperPosition position, BigDecimal lastPrice, boolean emergencyStopActive) {
        var marked = positions.mark(position, lastPrice);
        return positions.shouldExit(marked, lastPrice, emergencyStopActive)
            ? positions.close(marked, lastPrice)
            : marked;
    }
}
