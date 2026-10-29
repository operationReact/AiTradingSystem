package in.aitradingsystem.provider.angelone;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Retries only idempotent/read-only SmartAPI calls for transient provider failures.
 * Authentication/session failures, validation failures and provider rejections are never retried here.
 */
@Component
public class AngelOneRetryPolicy {
    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final AngelOneCallMetrics metrics;

    public AngelOneRetryPolicy(
            AngelOneCallMetrics metrics,
            @Value("${app.providers.angel-one.safe-read-retry.max-attempts:3}") int maxAttempts,
            @Value("${app.providers.angel-one.safe-read-retry.base-delay-ms:100}") long baseDelayMs,
            @Value("${app.providers.angel-one.safe-read-retry.max-delay-ms:1000}") long maxDelayMs) {
        if (maxAttempts < 1 || maxAttempts > 5) throw new IllegalArgumentException("maxAttempts must be between 1 and 5");
        if (baseDelayMs < 0 || baseDelayMs > 5_000) throw new IllegalArgumentException("baseDelayMs must be between 0 and 5000");
        if (maxDelayMs < baseDelayMs || maxDelayMs > 30_000) {
            throw new IllegalArgumentException("maxDelayMs must be >= baseDelayMs and <= 30000");
        }
        this.metrics = metrics;
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    public <T> T executeSafeRead(String endpoint, Supplier<T> call) {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (AngelOneException error) {
                if (!retryable(error) || attempt >= maxAttempts) throw error;
                metrics.recordRetry(endpoint, error.kind().name().toLowerCase());
                sleep(backoff(attempt));
            }
        }
    }

    static boolean retryable(AngelOneException error) {
        return error.kind() == AngelOneException.Kind.UNAVAILABLE
                || error.kind() == AngelOneException.Kind.RATE_LIMITED;
    }

    private Duration backoff(int failedAttempt) {
        long exponential = baseDelayMs == 0 ? 0 : Math.min(maxDelayMs, baseDelayMs * (1L << Math.min(20, failedAttempt - 1)));
        if (exponential == 0) return Duration.ZERO;
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, exponential / 2 + 1));
        return Duration.ofMillis(Math.min(maxDelayMs, exponential / 2 + jitter));
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) return;
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AngelOneException(AngelOneException.Kind.UNAVAILABLE, "RETRY_INTERRUPTED",
                    "Provider retry wait was interrupted");
        }
    }
}
