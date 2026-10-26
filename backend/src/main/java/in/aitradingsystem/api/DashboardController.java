package in.aitradingsystem.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DashboardController {
    @GetMapping("/dashboard/summary")
    public Map<String, Object> summary() {
        return Map.of(
            "mode", "PAPER", "liveTradingEnabled", false,
            "capital", new BigDecimal("80000.00"), "availableCash", new BigDecimal("80000.00"),
            "realizedPnl", BigDecimal.ZERO, "unrealizedPnl", BigDecimal.ZERO,
            "openPositions", 0, "signalsToday", 3, "systemStatus", "DEMO", "asOf", Instant.now().toString(),
            "agents", List.of(
                Map.of("id", "scanner", "name", "Market Scanner", "status", "DEMO", "description", "Finds liquid-stock candidates; live data adapter not configured."),
                Map.of("id", "news", "name", "News Analyst", "status", "DISABLED", "description", "News ingestion and Gemini integration are not configured."),
                Map.of("id", "strategy", "name", "Strategy Analyst", "status", "DEMO", "description", "Evaluates explicit strategy rules; demo signals are synthetic."),
                Map.of("id", "risk", "name", "Risk Manager", "status", "ACTIVE", "description", "Validates risk budgets and paper-only constraints."),
                Map.of("id", "research", "name", "Research Analyst", "status", "PLANNED", "description", "Backtesting and out-of-sample evaluation planned."),
                Map.of("id", "monitor", "name", "Paper Execution & Monitor", "status", "ACTIVE", "description", "Paper simulator only; live orders are not implemented.")
            )
        );
    }

    @GetMapping("/signals")
    public List<Map<String, Object>> signals() {
        var now = Instant.now().toString();
        return List.of(
            Map.of("id", "demo-1", "symbol", "RELIANCE", "action", "WATCH", "strategy", "trend_pullback_demo", "referencePrice", new BigDecimal("1400.00"), "stopLoss", new BigDecimal("1375.00"), "target", new BigDecimal("1450.00"), "confidence", 0.65, "reasonCodes", List.of("DEMO_DATA", "TREND_CHECK_PENDING"), "source", "SYNTHETIC_DEMO", "createdAt", now, "status", "NOT_EXECUTABLE"),
            Map.of("id", "demo-2", "symbol", "INFY", "action", "WATCH", "strategy", "breakout_demo", "referencePrice", new BigDecimal("1500.00"), "stopLoss", new BigDecimal("1470.00"), "target", new BigDecimal("1560.00"), "confidence", 0.52, "reasonCodes", List.of("DEMO_DATA", "VOLUME_CONFIRMATION_PENDING"), "source", "SYNTHETIC_DEMO", "createdAt", now, "status", "NOT_EXECUTABLE"),
            Map.of("id", "demo-3", "symbol", "TCS", "action", "NO_TRADE", "strategy", "news_watch_demo", "referencePrice", new BigDecimal("3800.00"), "stopLoss", new BigDecimal("0"), "target", new BigDecimal("0"), "confidence", 0.40, "reasonCodes", List.of("DEMO_DATA", "NEWS_SOURCE_NOT_CONNECTED"), "source", "SYNTHETIC_DEMO", "createdAt", now, "status", "NOT_EXECUTABLE")
        );
    }
}
