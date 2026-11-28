package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
import in.aitradingsystem.provider.upstox.CustomerUpstoxOAuthService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reconciles one reserved LIVE order against the owner's own broker session.
 * Three steps, so no database lock is held across broker I/O:
 * (1) read a snapshot, (2) read broker history with no transaction open,
 * (3) apply the decision under lock, failing if the order changed meanwhile.
 * It never retries or submits an order.
 */
@Service
public class LiveEquityReconciliationCoordinator {
    private final CustomerLiveEquityOrderService orders;
    private final CustomerExecutionReadinessService readiness;
    private final CustomerAngelOneSessionService angelOne;
    private final CustomerUpstoxOAuthService upstox;

    public LiveEquityReconciliationCoordinator(CustomerLiveEquityOrderService orders,
            CustomerExecutionReadinessService readiness,
            CustomerAngelOneSessionService angelOne, CustomerUpstoxOAuthService upstox) {
        this.orders = orders;
        this.readiness = readiness;
        this.angelOne = angelOne;
        this.upstox = upstox;
    }

    public CustomerLiveEquityOrderService.Order reconcile(UUID owner, UUID roomId, UUID orderId) {
        if (owner == null || roomId == null || orderId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reconcile request");
        }
        readiness.requireOwnedRoom(owner, roomId);
        var seen = orders.loadForReconcile(owner, roomId, orderId);
        LiveEquityOrderState current;
        try {
            current = LiveEquityOrderState.valueOf(seen.state());
        } catch (IllegalArgumentException invalidStoredState) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Stored order state requires operator review");
        }
        if (!current.requiresReconciliation()) {
            return orders.getOrder(owner, roomId, orderId);
        }
        // Broker I/O happens here, outside any database transaction.
        List<BrokerOrderRecord> records = readBrokerRecords(seen.broker(), owner, seen.brokerTag());
        var expected = new LiveEquityReconciler.Expected(seen.broker(), seen.brokerTag(),
            seen.exchange(), seen.tradingSymbol(), seen.side(), seen.quantity(), seen.limitPrice(),
            seen.brokerOrderId());
        LiveEquityReconciler.Resolution res = LiveEquityReconciler.reconcile(expected, records);
        return orders.applyReconciliation(owner, roomId, orderId, seen, res);
    }

    /**
     * The owner's own broker order book/history for this tag. Any provider failure
     * or absent session yields null, so the reconciler fails closed. Broker payloads
     * and errors are never logged or returned.
     */
    private List<BrokerOrderRecord> readBrokerRecords(String broker, UUID owner, String tag) {
        try {
            if ("UPSTOX".equals(broker)) {
                return UpstoxEquityOrderHistoryParser.parse(upstox.orderHistoryByTag(owner, tag).data());
            }
            if ("ANGEL_ONE".equals(broker)) {
                var body = angelOne.orders(owner).data();
                var array = body == null ? null : (body.isArray() ? body : body.path("data"));
                return AngelOneEquityOrderBookParser.parse(array);
            }
        } catch (RuntimeException providerUnavailable) {
            return null;
        }
        return null;
    }
}
