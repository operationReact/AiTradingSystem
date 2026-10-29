package in.aitradingsystem.api;

import in.aitradingsystem.common.PageResponse;
import in.aitradingsystem.config.RiskProperties;
import in.aitradingsystem.paper.PaperTradingService;
import in.aitradingsystem.risk.PositionSizeRequest;
import in.aitradingsystem.risk.PositionSizeSuggestion;
import in.aitradingsystem.risk.PositionSizingApplicationService;
import in.aitradingsystem.risk.RiskDecisionDetail;
import in.aitradingsystem.risk.RiskDecisionPersistenceService;
import in.aitradingsystem.risk.RiskDecisionSummary;
import in.aitradingsystem.risk.RiskMetrics;
import in.aitradingsystem.risk.RiskPolicyConfigService;
import in.aitradingsystem.risk.RiskPolicyView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final RiskDecisionPersistenceService decisions;
    private final PositionSizingApplicationService positionSizing;
    private final RiskPolicyConfigService policies;
    private final RiskMetrics metrics;

    public RiskController(
            RiskProperties props,
            PaperTradingService trading,
            RiskDecisionPersistenceService decisions,
            PositionSizingApplicationService positionSizing,
            RiskPolicyConfigService policies,
            RiskMetrics metrics) {
        this.props = props;
        this.trading = trading;
        this.decisions = decisions;
        this.positionSizing = positionSizing;
        this.policies = policies;
        this.metrics = metrics;
    }

    public record RiskConfigView(String tradingMode, boolean liveTradingEnabled, String policyVersion,
        BigDecimal startingCapital, BigDecimal riskPerTradePercent, BigDecimal maxDailyDrawdownPercent,
        int maxOpenPositions, BigDecimal feeRate, BigDecimal slippageRate, int signalMaxAgeMinutes,
        boolean newOrdersHalted) {}

    public record EmergencyStopRequest(@NotNull Boolean halted, @Size(max = 200) String reason) {}

    public record EmergencyStopView(boolean newOrdersHalted, String reason) {}

    /** Canonical shared-model endpoint for the active immutable persisted policy. */
    @GetMapping("/policy")
    public RiskPolicyView policy() {
        return policies.active();
    }

    @GetMapping("/config")
    public RiskConfigView config() {
        boolean halted = trading.isHalted();
        metrics.emergencyStop(halted);
        return new RiskConfigView("PAPER", false, in.aitradingsystem.risk.RiskPolicy.VERSION,
            props.defaultCapital(), props.riskPerTradePercent(), props.maxDailyDrawdownPercent(),
            props.maxOpenPositions(), props.feeRate(), props.slippageRate(), props.signalMaxAgeMinutes(),
            halted);
    }

    @GetMapping("/decisions")
    public PageResponse<RiskDecisionSummary> decisions(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return decisions.list(page, size);
    }

    @GetMapping("/decisions/{decisionId}")
    public RiskDecisionDetail decision(@PathVariable UUID decisionId) {
        return decisions.require(decisionId);
    }

    @PostMapping("/position-size")
    public PositionSizeSuggestion positionSize(@Valid @RequestBody PositionSizeRequest request) {
        return positionSizing.suggest(request);
    }

    @PutMapping("/emergency-stop")
    public EmergencyStopView emergencyStop(@Valid @RequestBody EmergencyStopRequest request) {
        String reason = normalizeReason(request.reason());
        if (request.halted() && (reason == null || reason.length() < 3)) {
            throw new IllegalArgumentException("A halt reason of at least 3 characters is required");
        }
        if (reason == null) reason = "Operator resumed trading";

        // PaperTradingService persists the requested state with an UPDATE, so repeating the same state remains idempotent.
        trading.setNewOrdersHalted(request.halted(), reason);
        boolean halted = trading.isHalted();
        metrics.emergencyStop(halted);
        return new EmergencyStopView(halted, reason);
    }

    private static String normalizeReason(String reason) {
        if (reason == null) return null;
        String normalized = reason.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRiskRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}
