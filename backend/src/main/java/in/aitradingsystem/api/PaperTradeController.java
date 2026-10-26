package in.aitradingsystem.api;

import in.aitradingsystem.domain.PaperTrade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/paper-trades")
public class PaperTradeController {
    private final CopyOnWriteArrayList<PaperTrade> trades = new CopyOnWriteArrayList<>();
    private static final BigDecimal CAPITAL = new BigDecimal("80000.00");
    private static final BigDecimal MAX_RISK = CAPITAL.multiply(new BigDecimal("0.0025"));
    private static final BigDecimal SLIPPAGE_RATE = new BigDecimal("0.001");

    public record SimulateRequest(@NotBlank @Pattern(regexp="[A-Z0-9&-]{1,20}") String symbol,
        @Pattern(regexp="BUY|SELL") String side, @Min(1) @jakarta.validation.constraints.Max(10000) int quantity,
        @DecimalMin("0.01") BigDecimal entryPrice, @DecimalMin("0.01") BigDecimal stopLoss,
        @DecimalMin("0.01") BigDecimal target) {}

    @GetMapping public List<PaperTrade> list() { return List.copyOf(trades); }

    @PostMapping("/simulate")
    public Map<String, Object> simulate(@Valid @RequestBody SimulateRequest request) {
        if (!"BUY".equals(request.side())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Initial simulator supports BUY-only cash-equity positions.");
        if (request.stopLoss().compareTo(request.entryPrice()) >= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "For BUY trades, stopLoss must be below entryPrice.");
        if (request.target().compareTo(request.entryPrice()) <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "For BUY trades, target must be above entryPrice.");
        BigDecimal risk = request.entryPrice().subtract(request.stopLoss()).multiply(BigDecimal.valueOf(request.quantity()));
        if (risk.compareTo(MAX_RISK) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Risk budget exceeded. Max per-trade risk is ₹" + MAX_RISK + ".");
        BigDecimal gross = request.entryPrice().multiply(BigDecimal.valueOf(request.quantity()));
        BigDecimal cost = gross.multiply(SLIPPAGE_RATE).setScale(2, RoundingMode.HALF_UP);
        if (gross.add(cost).compareTo(CAPITAL) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configured paper capital exceeded.");
        var trade = new PaperTrade(UUID.randomUUID().toString(), request.symbol(), request.side(), request.quantity(), request.entryPrice(), request.stopLoss(), request.target(), cost, risk, "OPEN_PAPER", Instant.now());
        trades.add(0, trade);
        return Map.of("mode", "PAPER", "liveTradingEnabled", false, "trade", trade, "message", "Synthetic paper trade recorded in memory; not persisted and not sent to a broker.");
    }
}
