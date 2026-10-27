package in.aitradingsystem.api;

import in.aitradingsystem.common.PageResponse;
import in.aitradingsystem.config.RiskProperties;
import in.aitradingsystem.paper.PaperQueryService;
import in.aitradingsystem.paper.PaperTradingService;
import in.aitradingsystem.paper.PaperViews.DecisionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/risk")
@Validated
public class RiskController {
    private final RiskProperties props;
    private final PaperTradingService trading;
    private final PaperQueryService queries;

    public RiskController(RiskProperties props, PaperTradingService trading, PaperQueryService queries) {
        this.props = props;
        this.trading = trading;
        this.queries = queries;
    }

    public record RiskConfigView(String tradingMode, boolean liveTradingEnabled, String policyVersion,
        BigDecimal startingCapital, BigDecimal riskPerTradePercent, BigDecimal maxDailyDrawdownPercent,
        int maxOpenPositions, BigDecimal feeRate, BigDecimal slippageRate, int signalMaxAgeMinutes,
        boolean newOrdersHalted) {}

    public record EmergencyStopRequest(@NotNull Boolean halted, @Size(max = 200) String reason) {}

    public record EmergencyStopView(boolean newOrdersHalted, String reason) {}

    @GetMapping("/config")
    public RiskConfigView config() {
        return new RiskConfigView("PAPER", false, in.aitradingsystem.risk.RiskPolicy.VERSION,
            props.defaultCapital(), props.riskPerTradePercent(), props.maxDailyDrawdownPercent(),
            props.maxOpenPositions(), props.feeRate(), props.slippageRate(), props.signalMaxAgeMinutes(),
            trading.isHalted());
    }

    @GetMapping("/decisions")
    public PageResponse<DecisionView> decisions(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.decisions(page, size);
    }

    @PutMapping("/emergency-stop")
    public EmergencyStopView emergencyStop(@Valid @RequestBody EmergencyStopRequest request) {
        String reason = request.reason() == null || request.reason().isBlank()
            ? (request.halted() ? "Operator emergency stop" : "Operator resumed trading") : request.reason();
        trading.setNewOrdersHalted(request.halted(), reason);
        return new EmergencyStopView(trading.isHalted(), reason);
    }
}