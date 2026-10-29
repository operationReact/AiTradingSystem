package in.aitradingsystem.provider.angelone;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Low-cardinality SmartAPI telemetry; never tags client code, symbol token, account, or credentials. */
@Component
public class AngelOneCallMetrics {
    private final MeterRegistry registry;

    public AngelOneCallMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public long start() {
        return System.nanoTime();
    }

    public void recordSuccess(String endpoint, long startedAtNanos) {
        record(endpoint, "success", startedAtNanos);
    }

    public void recordFailure(String endpoint, AngelOneException error, long startedAtNanos) {
        String outcome = error.kind().name().toLowerCase();
        record(endpoint, outcome, startedAtNanos);
        if (error.kind() == AngelOneException.Kind.RATE_LIMITED) {
            Counter.builder("aitrading.provider.angelone.throttled")
                    .description("Angel One requests rejected by local or provider throttling")
                    .tag("endpoint", endpoint)
                    .register(registry).increment();
        }
        if (error.kind() == AngelOneException.Kind.SESSION_EXPIRED) {
            recordAuthFailure("session_expired");
        } else if (AngelOneApiClient.LOGIN_PATH.equals(endpoint)
                && (error.kind() == AngelOneException.Kind.REJECTED
                    || error.kind() == AngelOneException.Kind.INVALID_RESPONSE)) {
            recordAuthFailure("login_failed");
        }
    }

    public void recordRetry(String endpoint, String reason) {
        Counter.builder("aitrading.provider.angelone.retries")
                .description("Bounded retries of safe Angel One read calls")
                .tag("endpoint", endpoint)
                .tag("reason", reason)
                .register(registry).increment();
    }

    public void recordAuthFailure(String type) {
        Counter.builder("aitrading.provider.angelone.auth_failures")
                .description("Angel One authentication or session failures")
                .tag("type", type)
                .register(registry).increment();
    }

    private void record(String endpoint, String outcome, long startedAtNanos) {
        Counter.builder("aitrading.provider.angelone.requests")
                .description("Angel One HTTP request count by stable endpoint and outcome")
                .tag("endpoint", endpoint)
                .tag("outcome", outcome)
                .register(registry).increment();
        Timer.builder("aitrading.provider.angelone.latency")
                .description("Angel One HTTP request latency")
                .tag("endpoint", endpoint)
                .tag("outcome", outcome)
                .register(registry)
                .record(Math.max(0L, System.nanoTime() - startedAtNanos), TimeUnit.NANOSECONDS);
    }
}
