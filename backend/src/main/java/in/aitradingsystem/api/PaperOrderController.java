package in.aitradingsystem.api;

import in.aitradingsystem.domain.ProductType;
import in.aitradingsystem.domain.Side;
import in.aitradingsystem.paper.OrderCommand;
import in.aitradingsystem.paper.PaperOrderService;
import in.aitradingsystem.paper.PaperViews.OrderResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Canonical Phase 6 PAPER order API. No LIVE submission path exists here. */
@RestController
@RequestMapping("/api/paper-orders")
@Validated
public class PaperOrderController {
    private final PaperOrderService orders;

    public PaperOrderController(PaperOrderService orders) {
        this.orders = orders;
    }

    public record CreateOrderRequest(
        @NotBlank @Pattern(regexp = "[A-Z0-9&-]{1,20}") String symbol,
        @NotNull @Pattern(regexp = "BUY|SELL") String side,
        @Min(1) @Max(10000) int quantity,
        @NotNull @DecimalMin("0.01") BigDecimal referencePrice,
        @DecimalMin("0.01") BigDecimal stopLoss,
        @DecimalMin("0.01") BigDecimal target,
        @NotNull Instant signalGeneratedAt,
        @NotNull @Pattern(regexp = "DELIVERY|INTRADAY") String productType,
        @NotNull @Pattern(regexp = "MARKET|LIMIT") String orderType,
        @DecimalMin("0.01") BigDecimal limitPrice) {}

    @PostMapping
    public ResponseEntity<OrderResult> create(
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 80) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        validateSupportedOrder(request);
        OrderCommand command = new OrderCommand(
            request.symbol(), Side.valueOf(request.side()), request.quantity(), request.referencePrice(),
            request.stopLoss(), request.target(), request.signalGeneratedAt(), ProductType.valueOf(request.productType()));
        OrderResult result = orders.placeOrder(command, idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    @GetMapping("/{id}")
    public OrderResult get(@PathVariable UUID id) {
        return orders.loadResult(id, false);
    }

    @PostMapping("/{id}/cancel")
    public OrderResult cancel(@PathVariable UUID id) {
        return orders.cancel(id);
    }

    private static void validateSupportedOrder(CreateOrderRequest request) {
        Side side = Side.valueOf(request.side());
        if (!"MARKET".equals(request.orderType())) {
            throw new IllegalArgumentException(
                "LIMIT PAPER execution is not yet supported; unsupported order combinations are rejected before intent creation");
        }
        if (request.limitPrice() != null) {
            throw new IllegalArgumentException("limitPrice is only valid for a supported LIMIT order");
        }
        if (side == Side.BUY) {
            if (request.stopLoss() == null || request.target() == null) {
                throw new IllegalArgumentException("BUY orders require stopLoss and target");
            }
            if (request.stopLoss().compareTo(request.referencePrice()) >= 0) {
                throw new IllegalArgumentException("For BUY orders, stopLoss must be below referencePrice");
            }
            if (request.target().compareTo(request.referencePrice()) <= 0) {
                throw new IllegalArgumentException("For BUY orders, target must be above referencePrice");
            }
        }
    }
}
