package in.aitradingsystem.execution;

import in.aitradingsystem.paper.OrderCommand;
import in.aitradingsystem.paper.PaperOrderService;
import in.aitradingsystem.paper.PaperViews.OrderResult;
import org.springframework.stereotype.Service;

/**
 * Single entry point for PAPER order initiation. Manual and automated callers are gated by the
 * selected execution mode, then converge on PaperOrderService so neither path can bypass risk,
 * approval policy, execution-time revalidation, accounting, or audit.
 */
@Service
public class PaperOrderPlacementService {
    private final TradingControlService controls;
    private final PaperOrderService paperOrders;

    public PaperOrderPlacementService(TradingControlService controls, PaperOrderService paperOrders) {
        this.controls = controls;
        this.paperOrders = paperOrders;
    }

    public OrderResult placeManual(OrderCommand command, String idempotencyKey) {
        return place(command, idempotencyKey, OrderExecutionMode.MANUAL);
    }

    public OrderResult placeAutomated(OrderCommand command, String idempotencyKey) {
        return place(command, idempotencyKey, OrderExecutionMode.AUTOMATED);
    }

    private OrderResult place(OrderCommand command, String idempotencyKey, OrderExecutionMode origin) {
        controls.requireExecutionMode(origin);
        return paperOrders.placeOrder(command, idempotencyKey);
    }
}
