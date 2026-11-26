package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
import in.aitradingsystem.instrument.InstrumentMasterException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Both modes can inspect execution readiness and validate order intent.
 * The reserved LIVE stock route currently validates and then fails closed;
 * preflight never trades. Broker selection stores a room preference only.
 */
@RestController
@Validated
@RequestMapping("/api/customer/trading-rooms/{roomId}")
public class CustomerExecutionReadinessController {
    private final CustomerExecutionReadinessService service;
    private final CustomerLiveEquityOrderService liveEquityOrders;
    private final CustomerIdentityStore identities;

    public CustomerExecutionReadinessController(
            CustomerExecutionReadinessService service,
            CustomerLiveEquityOrderService liveEquityOrders,
            CustomerIdentityStore identities) {
        this.service = service;
        this.liveEquityOrders = liveEquityOrders;
        this.identities = identities;
    }

    public record LiveBrokerSelectionRequest(
        @NotNull @Pattern(regexp = "ANGEL_ONE|UPSTOX") String broker) {}

    /** Stock intraday LIVE route capability; informational and always fail-closed in this release. */
    public record LiveEquityCapability(UUID roomId, String selectedBroker,
            boolean brokerConnected, boolean orderRouteImplemented,
            boolean executionEnabled, List<String> blockers) {}

    public record LiveEquityOrderRequest(
        @NotNull @Min(1) Long instrumentId,
        @NotNull @Pattern(regexp = "BUY|SELL") String side,
        @NotNull @Min(1) @Max(10000) Integer quantity,
        @NotNull @DecimalMin("0.01") @DecimalMax("10000000") @Digits(integer=8, fraction=2)
            BigDecimal limitPrice) {}

    public record OrderPreflightRequest(
        @NotNull @Min(1) Long instrumentId,
        @NotNull @Pattern(regexp = "BUY|SELL") String side,
        @NotNull @Min(1) @Max(10000) Integer quantity,
        @NotNull @DecimalMin("0.01") @DecimalMax("10000000") @Digits(integer=8, fraction=2)
            BigDecimal limitPrice) {}

    @GetMapping("/execution/readiness")
    public ResponseEntity<CustomerExecutionReadinessService.Readiness> readiness(
            @PathVariable UUID roomId, Authentication authentication) {
        return noStore(service.readiness(customer(authentication), roomId));
    }

    @GetMapping("/live/equity/capability")
    public ResponseEntity<LiveEquityCapability> liveEquityCapability(
            @PathVariable UUID roomId, Authentication authentication) {
        var readiness = service.readiness(customer(authentication), roomId);
        var blockers = new java.util.ArrayList<>(readiness.blockers());
        blockers.add("LIVE_INTRADAY_EQUITY_ORDER_ROUTE_NOT_IMPLEMENTED");
        blockers.add("AUTHORITATIVE_QUOTE_NOT_VERIFIED_FOR_ORDER");
        blockers.add("ACCOUNT_WIDE_RISK_POLICY_NOT_ENABLED");
        blockers.add("BROKER_ORDER_DISPATCH_AND_RECONCILIATION_NOT_IMPLEMENTED");
        blockers.add("ANGEL_ONE_EQUITY_DISPATCH_NOT_COORDINATED");
        blockers.add("UPSTOX_EQUITY_DISPATCH_NOT_COORDINATED");
        return noStore(new LiveEquityCapability(roomId, readiness.selectedBroker(),
            readiness.brokerConnected(), false, false, List.copyOf(blockers)));
    }

    /**
     * Explicitly reserved route for stock intraday LIVE orders. It validates
     * owner, room, equity instrument and intent, then fails closed until
     * authoritative quote, account-wide risk, supported broker dispatch and
     * reconciliation are wired.
     */
    @PostMapping("/live/equity/orders")
    public ResponseEntity<Void> submitLiveEquityOrder(
            @PathVariable UUID roomId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String idempotencyKey,
            @RequestHeader(value="X-Confirm-Live-Equity-Order", required=false) String confirmation,
            @Valid @RequestBody LiveEquityOrderRequest input,
            Authentication authentication) {
        var owner = customer(authentication);
        // Run the same authoritative validation as the read-only preflight.
        // This prevents the reserved route from accepting non-equity symbols,
        // malformed quantities or orders in F&O-only rooms.
        service.preflight(owner, roomId, input.instrumentId(), input.side(),
            input.quantity(), input.limitPrice());
        if (!"CONFIRM_LIVE_INTRADAY_EQUITY".equals(confirmation)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                "Explicit per-order LIVE equity confirmation required");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "LIVE_INTRADAY_EQUITY_ORDER_ROUTE_NOT_IMPLEMENTED; no order was sent to a broker");
    }

    @GetMapping("/live/equity/orders")
    public ResponseEntity<List<CustomerLiveEquityOrderService.Order>> liveEquityOrders(
            @PathVariable UUID roomId, Authentication authentication) {
        return noStore(liveEquityOrders.list(customer(authentication), roomId));
    }

    /**
     * Owner-scoped reconciliation of one reserved LIVE equity order against the
     * authenticated customer's own selected-broker order book. It never submits
     * or retries an order; it only reads the broker to prove a state, and fails
     * closed (the order stays reserved) on any missing or contradictory evidence.
     * No order can reach a reconcilable state while dispatch is disabled.
     */
    @PostMapping("/live/equity/orders/{orderId}/reconcile")
    public ResponseEntity<CustomerLiveEquityOrderService.Order> reconcileLiveEquityOrder(
            @PathVariable UUID roomId, @PathVariable UUID orderId,
            Authentication authentication) {
        return noStore(liveEquityOrders.reconcileFromBroker(
            customer(authentication), roomId, orderId));
    }

    @PostMapping("/execution/broker-selection")
    public ResponseEntity<CustomerExecutionReadinessService.Readiness> chooseBroker(
            @PathVariable UUID roomId,
            @Valid @RequestBody LiveBrokerSelectionRequest input,
            Authentication authentication) {
        return noStore(service.selectLiveBroker(
            customer(authentication), roomId, input.broker()));
    }

    @PostMapping("/orders/preflight")
    public ResponseEntity<CustomerExecutionReadinessService.Preflight> preflight(
            @PathVariable UUID roomId,
            @Valid @RequestBody OrderPreflightRequest input,
            Authentication authentication) {
        return noStore(service.preflight(customer(authentication), roomId,
            input.instrumentId(), input.side(), input.quantity(), input.limitPrice()));
    }

    @ExceptionHandler(InstrumentMasterException.class)
    ProblemDetail instrumentRejected(InstrumentMasterException ex) {
        HttpStatus status = "INSTRUMENT_NOT_FOUND".equals(ex.code())
            ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        detail.setProperty("code", ex.code());
        return detail;
    }

    private UUID customer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream()
                    .noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer session required");
        }
        return identities.find("cognito", token.getToken().getIssuer().toString(),
            token.getToken().getSubject())
            .filter(a -> "ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Customer not linked"));
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
