package in.aitradingsystem.api;

import in.aitradingsystem.common.PageResponse;
import in.aitradingsystem.domain.Side;
import in.aitradingsystem.paper.OrderCommand;
import in.aitradingsystem.paper.PaperQueryService;
import in.aitradingsystem.paper.PaperTradingService;
import in.aitradingsystem.paper.PaperViews.OrderResult;
import in.aitradingsystem.paper.PaperViews.OrderView;
import in.aitradingsystem.paper.PaperViews.PortfolioView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Paper-only order API. There is no live-order path. */
@RestController
@RequestMapping("/api/paper-trades")
@Validated
public class PaperTradeController {
    private final PaperTradingService trading;
    private final PaperQueryService queries;

    public PaperTradeController(PaperTradingService trading, PaperQueryService queries) {
        this.trading = trading;
        this.queries = queries;
    }

    public record SimulateRequest(
        @NotBlank @Pattern(regexp = "[A-Z0-9&-]{1,20}") String symbol,
        @NotNull @Pattern(regexp = "BUY|SELL") String side,
        @Min(1) @Max(10000) int quantity,
        @NotNull @DecimalMin("0.01") BigDecimal entryPrice,
        @DecimalMin("0.01") BigDecimal stopLoss,
        @DecimalMin("0.01") BigDecimal target,
        @NotNull Instant signalGeneratedAt) {}

    @PostMapping("/simulate")
    public ResponseEntity<?> simulate(@RequestHeader("Idempotency-Key") @Size(min = 8, max = 80) String idempotencyKey,
                                      @Valid @RequestBody SimulateRequest request) {
        Side side = Side.valueOf(request.side());
        if (side == Side.BUY) {
            if (request.stopLoss() == null || request.target() == null) {
                throw new IllegalArgumentException("BUY orders require stopLoss and target");
            }
            if (request.stopLoss().compareTo(request.entryPrice()) >= 0) {
                throw new IllegalArgumentException("For BUY orders, stopLoss must be below entryPrice");
            }
            if (request.target().compareTo(request.entryPrice()) <= 0) {
                throw new IllegalArgumentException("For BUY orders, target must be above entryPrice");
            }
        }
        OrderCommand command = new OrderCommand(request.symbol(), side, request.quantity(), request.entryPrice(),
            request.stopLoss(), request.target(), request.signalGeneratedAt());
        OrderResult result = trading.placeOrder(command, idempotencyKey);

        if ("REJECTED".equals(result.order().status())) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                "Risk Manager rejected the order: " + result.order().rejectionReason());
            pd.setType(URI.create("about:blank"));
            pd.setProperty("orderId", result.order().id());
            pd.setProperty("reasonCodes", result.decision() == null ? java.util.List.of() : result.decision().reasonCodes());
            return ResponseEntity.unprocessableEntity().body(pd);
        }
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    @GetMapping
    public PageResponse<OrderView> list(
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(required = false) @Pattern(regexp = "NEW|RISK_APPROVED|REJECTED|FILLED") String status,
        @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9&-]{1,20}") String symbol) {
        return queries.orders(status, symbol, page, size);
    }

    @GetMapping("/portfolio")
    public PortfolioView portfolio() {
        return queries.portfolio();
    }

    @GetMapping("/{id}")
    public OrderResult get(@PathVariable UUID id) {
        return queries.order(id);
    }
}