package in.aitradingsystem.trading;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class PaperLifecycleEventMapper {
    public PaperLifecycleEvent position(String accountId, PaperPosition position) {
        if (position.open()) {
            return new PaperLifecycleEvent(accountId, position.symbol(), "Position open", position.unrealizedPnl(),
                "Your PAPER position is open and being monitored.");
        }
        var pnl = position.realizedPnl() == null ? BigDecimal.ZERO : position.realizedPnl();
        var outcome = pnl.signum() >= 0 ? "Profit" : "Loss";
        return new PaperLifecycleEvent(accountId, position.symbol(), "Closed", pnl,
            "PAPER position closed. " + outcome + " recorded in your history.");
    }

    public PaperLifecycleEvent paused(String accountId, String symbol) {
        return new PaperLifecycleEvent(accountId, symbol, "Trading paused", BigDecimal.ZERO,
            "Trading is paused by your safety controls.");
    }
}
