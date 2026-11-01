package in.aitradingsystem.trading;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class PaperCapitalService {
    public PaperCapitalSnapshot initial(BigDecimal startingCapital) {
        if (startingCapital == null || startingCapital.signum() <= 0) throw new IllegalArgumentException("Starting PAPER capital must be positive.");
        return new PaperCapitalSnapshot(startingCapital, startingCapital, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public PaperCapitalSnapshot onOpen(PaperCapitalSnapshot capital, PaperPosition position) {
        var committed = position.entryPrice().multiply(BigDecimal.valueOf(position.quantity()));
        if (committed.compareTo(capital.availableCapital()) > 0) throw new IllegalStateException("Insufficient PAPER buying power.");
        return new PaperCapitalSnapshot(capital.startingCapital(), capital.availableCapital().subtract(committed),
            capital.committedCapital().add(committed), capital.realizedPnl());
    }

    public PaperCapitalSnapshot onClose(PaperCapitalSnapshot capital, PaperPosition closedPosition) {
        if (closedPosition.open()) throw new IllegalArgumentException("Position must be closed before releasing PAPER capital.");
        var committed = closedPosition.entryPrice().multiply(BigDecimal.valueOf(closedPosition.quantity()));
        var released = committed.add(closedPosition.realizedPnl());
        return new PaperCapitalSnapshot(capital.startingCapital(), capital.availableCapital().add(released),
            capital.committedCapital().subtract(committed), capital.realizedPnl().add(closedPosition.realizedPnl()));
    }
}
