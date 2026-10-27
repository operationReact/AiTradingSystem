package in.aitradingsystem.api;

import in.aitradingsystem.strategy.StrategySignal;
import in.aitradingsystem.strategy.StrategySignalRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/signals")
public class SignalController {
    private final StrategySignalRepository signals;

    public SignalController(StrategySignalRepository signals) {
        this.signals = signals;
    }

    /** Returns persisted deterministic candidates only; this endpoint never fabricates demo data or places orders. */
    @GetMapping
    public List<StrategySignal> signals(@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return signals.recent(limit);
    }
}
