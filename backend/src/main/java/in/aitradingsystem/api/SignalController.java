package in.aitradingsystem.api;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.strategy.StrategyEvaluation;
import in.aitradingsystem.strategy.StrategyService;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@Validated
@RequestMapping("/api/signals")
public class SignalController {
    private final StrategyService strategies;

    public SignalController(StrategyService strategies) {
        this.strategies = strategies;
    }

    /** Read-only evaluation uses stored candles only. No sample prices or orders are fabricated. */
    @GetMapping
    public List<StrategyEvaluation> signals(
            @RequestParam @Min(1) long instrumentId,
            @RequestParam(defaultValue = "FIVE_MINUTE") HistoricalCandleInterval interval) {
        return List.of(strategies.evaluate(instrumentId, interval));
    }
}
