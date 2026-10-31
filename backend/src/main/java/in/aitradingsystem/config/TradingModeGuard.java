package in.aitradingsystem.config;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Ensures this build never starts configured as LIVE. The application has no live broker-order adapter,
 * so paper-only remains the only safe execution mode until that capability is implemented and reviewed.
 */
@Component
@DependsOn("flywayInitializer")
public class TradingModeGuard {
    public TradingModeGuard(JdbcClient jdbc) {
        String activeMode = jdbc.sql("SELECT active_mode FROM trading_execution_mode WHERE id = 1")
            .query(String.class).optional().orElse("PAPER");
        if (!"PAPER".equals(activeMode)) {
            throw new IllegalStateException("Unsupported active trading mode '" + activeMode
                + "'. This build has no live broker order adapter; only PAPER is allowed.");
        }
    }
}
