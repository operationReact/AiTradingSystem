package in.aitradingsystem.trading;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class PaperPositionService {
    public PaperPosition open(SimulatedPaperOrder order) {
        return new PaperPosition(order.orderId(), order.symbol(), order.side(), order.quantity(), order.fillPrice(),
            order.stopLoss(), order.target(), order.fillPrice(), BigDecimal.ZERO, BigDecimal.ZERO, true);
    }

    public PaperPosition mark(PaperPosition position, BigDecimal lastPrice) {
        if (!position.open()) return position;
        var pnl = pnl(position, lastPrice);
        return new PaperPosition(position.orderId(), position.symbol(), position.side(), position.quantity(),
            position.entryPrice(), position.stopLoss(), position.target(), lastPrice, pnl, BigDecimal.ZERO, true);
    }

    public boolean shouldExit(PaperPosition position, BigDecimal lastPrice, boolean emergencyStopActive) {
        if (!position.open()) return false;
        if (emergencyStopActive) return true;
        if ("SELL".equalsIgnoreCase(position.side())) {
            return lastPrice.compareTo(position.stopLoss()) >= 0 || lastPrice.compareTo(position.target()) <= 0;
        }
        return lastPrice.compareTo(position.stopLoss()) <= 0 || lastPrice.compareTo(position.target()) >= 0;
    }

    public PaperPosition close(PaperPosition position, BigDecimal exitPrice) {
        var realized = pnl(position, exitPrice);
        return new PaperPosition(position.orderId(), position.symbol(), position.side(), position.quantity(),
            position.entryPrice(), position.stopLoss(), position.target(), exitPrice, BigDecimal.ZERO, realized, false);
    }

    private BigDecimal pnl(PaperPosition position, BigDecimal price) {
        var perShare = "SELL".equalsIgnoreCase(position.side())
            ? position.entryPrice().subtract(price)
            : price.subtract(position.entryPrice());
        return perShare.multiply(BigDecimal.valueOf(position.quantity()));
    }
}
