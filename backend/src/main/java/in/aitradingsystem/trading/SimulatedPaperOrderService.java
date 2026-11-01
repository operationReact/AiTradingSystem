package in.aitradingsystem.trading;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** PAPER-only simulated order/fill service. It has no broker adapter dependency. */
@Service
public class SimulatedPaperOrderService {
    private final Map<String, SimulatedPaperOrder> byIdempotencyKey = new ConcurrentHashMap<>();

    public SimulatedPaperOrder fill(PaperTradeIntent intent) {
        if (intent == null || intent.idempotencyKey() == null || intent.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("A PAPER trade idempotency key is required.");
        }
        return byIdempotencyKey.computeIfAbsent(intent.idempotencyKey(), key -> new SimulatedPaperOrder(
            UUID.randomUUID().toString(), key, intent.symbol(), intent.side(), intent.quantity(), intent.entryPrice(),
            intent.stopLoss(), intent.target(), PaperOrderState.FILLED));
    }
}
