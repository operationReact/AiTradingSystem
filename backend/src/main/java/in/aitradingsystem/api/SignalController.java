package in.aitradingsystem.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Synthetic demo signals until the Strategy Analyst is implemented. Never executable. */
@RestController
@RequestMapping("/api/signals")
public class SignalController {
    public record DemoSignal(String id, String symbol, String action, String strategy, BigDecimal referencePrice,
        BigDecimal stopLoss, BigDecimal target, double confidence, List<String> reasonCodes, String source,
        Instant createdAt, String status) {}

    @GetMapping
    public List<DemoSignal> signals() {
        Instant now = Instant.now();
        return List.of(
            new DemoSignal("demo-1", "RELIANCE", "WATCH", "trend_pullback_demo", new BigDecimal("1400.00"),
                new BigDecimal("1375.00"), new BigDecimal("1450.00"), 0.65,
                List.of("DEMO_DATA", "TREND_CHECK_PENDING"), "SYNTHETIC_DEMO", now, "NOT_EXECUTABLE"),
            new DemoSignal("demo-2", "INFY", "WATCH", "breakout_demo", new BigDecimal("1500.00"),
                new BigDecimal("1470.00"), new BigDecimal("1560.00"), 0.52,
                List.of("DEMO_DATA", "VOLUME_CONFIRMATION_PENDING"), "SYNTHETIC_DEMO", now, "NOT_EXECUTABLE"),
            new DemoSignal("demo-3", "TCS", "NO_TRADE", "news_watch_demo", new BigDecimal("3800.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, 0.40,
                List.of("DEMO_DATA", "NEWS_SOURCE_NOT_CONNECTED"), "SYNTHETIC_DEMO", now, "NOT_EXECUTABLE"));
    }
}