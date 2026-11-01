package in.aitradingsystem.paper;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.domain.ProductType;
import in.aitradingsystem.domain.Side;
import in.aitradingsystem.execution.TradingExecutionAuthority;
import in.aitradingsystem.strategy.StrategySignal;
import in.aitradingsystem.strategy.StrategySignalRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Converts deterministic BUY candidates into standard PAPER order commands only when the
 * global authority is PAPER + AUTOMATED. Every submitted command goes through
 * PaperOrderService, so risk, approval policy and execution-readiness checks remain mandatory.
 */
@Service
public class AutomatedPaperOrderService {
    private final TradingExecutionAuthority authority;
    private final StrategySignalRepository signals;
    private final PaperOrderService orders;
    private final AuditService audit;
    private final int defaultQuantity;
    private final int scanLimit;

    public AutomatedPaperOrderService(
            TradingExecutionAuthority authority,
            StrategySignalRepository signals,
            PaperOrderService orders,
            AuditService audit,
            @Value("${app.paper.automation.default-quantity:1}") int defaultQuantity,
            @Value("${app.paper.automation.scan-limit:20}") int scanLimit) {
        if (defaultQuantity < 1) throw new IllegalArgumentException("defaultQuantity must be positive");
        if (scanLimit < 1 || scanLimit > 100) throw new IllegalArgumentException("scanLimit must be between 1 and 100");
        this.authority = authority;
        this.signals = signals;
        this.orders = orders;
        this.audit = audit;
        this.defaultQuantity = defaultQuantity;
        this.scanLimit = scanLimit;
    }

    public RunResult runOnce() {
        var mode = authority.current();
        if (!mode.automatedPaper()) {
            return new RunResult(false, 0, 0, 0);
        }

        int eligible = 0;
        int submitted = 0;
        int failed = 0;
        for (StrategySignal signal : signals.recent(scanLimit)) {
            if (!eligible(signal)) continue;
            eligible++;
            try {
                OrderCommand command = new OrderCommand(
                    signal.symbol(),
                    Side.BUY,
                    defaultQuantity,
                    signal.referencePrice(),
                    signal.stopLoss(),
                    signal.target(),
                    signal.createdAt(),
                    ProductType.INTRADAY,
                    null,
                    "AUTOMATED",
                    "STRATEGY_SIGNAL");
                var result = orders.placeOrder(command, idempotencyKey(signal));
                submitted++;
                audit.record("AUTOMATED_PAPER_SIGNAL_SUBMITTED", "STRATEGY_SIGNAL", signal.id(), Map.of(
                    "symbol", signal.symbol(),
                    "strategy", signal.strategy(),
                    "quantity", defaultQuantity,
                    "replayed", result.replayed()));
            } catch (RuntimeException ex) {
                failed++;
                audit.record("AUTOMATED_PAPER_SIGNAL_FAILED", "STRATEGY_SIGNAL", signal.id(), Map.of(
                    "symbol", signal.symbol(),
                    "strategy", signal.strategy(),
                    "reason", ex.getClass().getSimpleName()));
            }
        }
        return new RunResult(true, eligible, submitted, failed);
    }

    static String idempotencyKey(StrategySignal signal) {
        return "auto-paper-signal:" + signal.id();
    }

    private static boolean eligible(StrategySignal signal) {
        return signal != null
            && "BUY".equals(signal.action())
            && positive(signal.referencePrice())
            && positive(signal.stopLoss())
            && positive(signal.target())
            && signal.createdAt() != null;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    public record RunResult(boolean automationEnabled, int eligibleSignals, int submitted, int failed) {}
}
