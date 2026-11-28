package in.aitradingsystem.tradingrooms;

import com.fasterxml.jackson.databind.node.ObjectNode;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.AngelOneIntradayEquityOrderPayload;
import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
import in.aitradingsystem.provider.upstox.CustomerUpstoxOAuthService;
import in.aitradingsystem.provider.upstox.UpstoxIntradayEquityOrderPayload;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The only path to a LIVE intraday cash-equity broker request. Ordering is
 * fixed and deliberate:
 *
 * <ol>
 *   <li>Deployment dispatch gate. Off means no claim and no broker request.</li>
 *   <li>Durable claim (its own committed transaction): preflight recomputed,
 *       account-wide risk checked, idempotency key and unique correlation tag
 *       persisted in SUBMITTING. An idempotent replay returns the stored order
 *       and never dispatches.</li>
 *   <li>Pre-send validation of the broker payload. A failure here is recorded as
 *       REJECTED, which is provably safe because no request was made.</li>
 *   <li>Exactly ONE broker request. No retry at any layer. A timeout, throttle,
 *       5xx or any ambiguous response is recorded as UNKNOWN.</li>
 *   <li>One compare-and-set outcome write (its own committed transaction).
 *       UNKNOWN and SUBMITTING orders stay reserved until reconciliation proves
 *       a state against the broker's own order book.</li>
 * </ol>
 *
 * <p>Broker credentials, raw provider responses and exception text are never
 * placed in the returned value or in logs.
 */
@Service
public class LiveEquityOrderCoordinator {
    /** Exact per-order consent phrase required in the request header. */
    public static final String CONSENT = "CONFIRM_LIVE_INTRADAY_EQUITY";

    private final CustomerLiveEquityOrderService orders;
    private final CustomerExecutionReadinessService readiness;
    private final InstrumentMasterService instruments;
    private final CustomerAngelOneSessionService angelOne;
    private final CustomerUpstoxOAuthService upstox;
    private final LiveEquityExecutionPolicy policy;

    public LiveEquityOrderCoordinator(CustomerLiveEquityOrderService orders,
            CustomerExecutionReadinessService readiness,
            InstrumentMasterService instruments, CustomerAngelOneSessionService angelOne,
            CustomerUpstoxOAuthService upstox, LiveEquityExecutionPolicy policy) {
        this.orders = orders;
        this.readiness = readiness;
        this.instruments = instruments;
        this.angelOne = angelOne;
        this.upstox = upstox;
        this.policy = policy == null ? LiveEquityExecutionPolicy.DISABLED : policy;
    }

    /** {@code created=false} is an idempotent replay; no broker request was made on this call. */
    public record Dispatch(CustomerLiveEquityOrderService.Order order, boolean created) {}

    /** Result of the single broker request (or of a pre-send rejection). */
    private record Outcome(LiveEquityOrderState state, String brokerOrderId, String code) {}

    /**
     * Ordering: gate, consent, replay lookup (no broker call), broker-backed
     * preflight (outside any database transaction), fail-fast blocker check, the
     * database-only claim, then exactly one broker request. No step retries.
     */
    public Dispatch submit(UUID owner, UUID roomId, String idempotencyKey,
            long instrumentId, String side, int quantity, BigDecimal limitPrice,
            String confirmation) {
        return submit(owner, roomId, idempotencyKey, instrumentId, side, quantity,
            limitPrice, confirmation, null);
    }

    /**
     * Strategy proposals pin the broker the owner reviewed. The expected broker
     * is checked against the fresh preflight before the durable claim; the claim
     * then rechecks the same broker under the owner lock.
     */
    public Dispatch submit(UUID owner, UUID roomId, String idempotencyKey,
            long instrumentId, String side, int quantity, BigDecimal limitPrice,
            String confirmation, String expectedBroker) {
        if (!policy.dispatchEnabled()) {
            // Gate off: refuse before anything is read from a broker or written.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "LIVE intraday equity preflight is not executable");
        }
        if (!CONSENT.equals(confirmation)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                "Explicit per-order LIVE equity confirmation required");
        }
        var replay = orders.findReplay(owner, roomId, idempotencyKey, instrumentId, side,
            quantity, limitPrice);
        if (replay.isPresent()) {
            return new Dispatch(replay.get().order(), false);
        }
        // Broker-backed reads happen here, with no database transaction open.
        CustomerExecutionReadinessService.Preflight proposal =
            readiness.preflight(owner, roomId, instrumentId, side, quantity, limitPrice);
        if (!proposal.blockers().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "LIVE intraday equity preflight is not executable");
        }
        if (expectedBroker != null && !expectedBroker.equals(proposal.selectedBroker())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Selected LIVE broker changed after strategy proposal review");
        }
        CustomerLiveEquityOrderService.Claim claim = orders.claimForSubmission(owner,
            roomId, idempotencyKey, proposal, true);
        if (!claim.created()) {
            return new Dispatch(claim.order(), false);
        }
        Outcome outcome = dispatchOnce(owner, instrumentId, claim);
        CustomerLiveEquityOrderService.Order recorded = orders.recordDispatchOutcome(owner,
            roomId, claim.order().id(), outcome.state(), outcome.brokerOrderId(), outcome.code());
        return new Dispatch(recorded, true);
    }

    /**
     * Build and send exactly one request for an already-committed SUBMITTING claim.
     * Nothing in this method retries.
     */
    private Outcome dispatchOnce(UUID owner, long instrumentId,
            CustomerLiveEquityOrderService.Claim claim) {
        CustomerLiveEquityOrderService.Order order = claim.order();
        // Pre-send phase: no request exists yet, so a failure here is a provable rejection.
        Instrument instrument;
        ObjectNode payload;
        try {
            instrument = instruments.requireActiveInstrument(instrumentId);
            if ("ANGEL_ONE".equals(order.broker())) {
                payload = AngelOneIntradayEquityOrderPayload.build(
                    new AngelOneIntradayEquityOrderPayload.VerifiedOrder(order.exchange(),
                        instrument.symbolToken(), order.tradingSymbol(), order.side(),
                        order.quantity(), order.limitPrice(), claim.brokerTag()));
            } else if ("UPSTOX".equals(order.broker())) {
                String instrumentKey = upstox.cashEquityInstrumentKey(instrument);
                payload = new UpstoxIntradayEquityOrderPayload().build(
                    new UpstoxIntradayEquityOrderPayload.VerifiedOrder(instrumentKey,
                        order.side(), order.quantity(), order.limitPrice(), claim.brokerTag()));
            } else {
                return new Outcome(LiveEquityOrderState.REJECTED, null,
                    "BROKER_NOT_SUPPORTED");
            }
        } catch (RuntimeException invalidBeforeSend) {
            return new Outcome(LiveEquityOrderState.REJECTED, null, "ORDER_PAYLOAD_NOT_BUILT");
        }
        // Single broker request. Any exception from the transport is ambiguous.
        try {
            return sendOnce(owner, order, payload);
        } catch (RuntimeException ambiguous) {
            return new Outcome(LiveEquityOrderState.UNKNOWN, null, "BROKER_DISPATCH_UNCERTAIN");
        }
    }

    private Outcome sendOnce(UUID owner, CustomerLiveEquityOrderService.Order order,
            ObjectNode payload) {
        if ("ANGEL_ONE".equals(order.broker())) {
            var result = angelOne.submitVerifiedIntradayLimitOnce(owner, payload);
            return map(result.outcome(), result.orderId(), result.code());
        }
        var result = upstox.submitVerifiedEquityIntradayOnce(owner, payload);
        return map(result.outcome(), result.orderId(), result.code());
    }

    private static Outcome map(String outcome, String brokerOrderId, String code) {
        String safeCode = code == null || !code.matches("[A-Z0-9_]{1,64}")
            ? "BROKER_OUTCOME_UNMAPPED" : code;
        return switch (outcome == null ? "" : outcome) {
            case "ACKNOWLEDGED" -> new Outcome(LiveEquityOrderState.ACKNOWLEDGED,
                brokerOrderId, safeCode);
            case "REJECTED" -> new Outcome(LiveEquityOrderState.REJECTED, null, safeCode);
            // Anything else, including an unexpected value, is treated as ambiguous.
            default -> new Outcome(LiveEquityOrderState.UNKNOWN, null, safeCode);
        };
    }
}
