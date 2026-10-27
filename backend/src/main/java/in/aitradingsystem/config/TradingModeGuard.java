package in.aitradingsystem.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fails startup unless the application runs in paper mode. No live order path exists in this codebase. */
@Component
public class TradingModeGuard {
    public TradingModeGuard(@Value("${app.trading-mode:PAPER}") String tradingMode) {
        if (!"PAPER".equals(tradingMode)) {
            throw new IllegalStateException("Unsupported trading mode '" + tradingMode + "'. Only PAPER is allowed in this build.");
        }
    }
}