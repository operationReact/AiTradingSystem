package in.aitradingsystem.provider.angelone;

import in.aitradingsystem.config.AngelOneProperties;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Process-local request throttle. Normal traffic fails fast. Session recovery can wait only for a configured,
 * bounded duration; provider/network failures are never retried by this component.
 */
@Component
public class AngelOneRateLimiter {
    public enum Category { AUTH, MARKET_QUOTE, HISTORICAL_CANDLES }

    private final Map<Category, Long> minIntervalNanos = new EnumMap<>(Category.class);
    private final Map<Category, Long> lastAcquiredNanos = new EnumMap<>(Category.class);
    private final long recoveryMaxWaitMs;

    public AngelOneRateLimiter(AngelOneProperties properties) {
        minIntervalNanos.put(Category.AUTH, TimeUnit.MILLISECONDS.toNanos(properties.loginMinIntervalMs()));
        minIntervalNanos.put(Category.MARKET_QUOTE, TimeUnit.MILLISECONDS.toNanos(properties.quoteMinIntervalMs()));
        // Conservative local pacing for historical candles; account limits can vary.
        minIntervalNanos.put(Category.HISTORICAL_CANDLES, TimeUnit.MILLISECONDS.toNanos(1_000));
        recoveryMaxWaitMs = properties.sessionRetryMaxWaitMs();
    }

    public synchronized void acquire(Category category) {
        long now = System.nanoTime();
        if (!allowed(category, now)) {
            throw rateLimited(category);
        }
        lastAcquiredNanos.put(category, now);
    }

    public synchronized void acquireForRecovery(Category category) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(recoveryMaxWaitMs);
        while (true) {
            long now = System.nanoTime();
            if (allowed(category, now)) {
                lastAcquiredNanos.put(category, now);
                return;
            }
            long remaining = deadline - now;
            if (remaining <= 0) {
                throw rateLimited(category);
            }
            long elapsed = now - lastAcquiredNanos.get(category);
            long waitNanos = Math.min(remaining, minIntervalNanos.get(category) - elapsed);
            try {
                TimeUnit.NANOSECONDS.timedWait(this, waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AngelOneException(AngelOneException.Kind.UNAVAILABLE, "RECOVERY_WAIT_INTERRUPTED",
                        "Session recovery was interrupted");
            }
        }
    }

    private boolean allowed(Category category, long now) {
        Long last = lastAcquiredNanos.get(category);
        return last == null || now - last >= minIntervalNanos.get(category);
    }

    private static AngelOneException rateLimited(Category category) {
        return new AngelOneException(AngelOneException.Kind.RATE_LIMITED, "LOCAL_RATE_LIMIT",
                "Local rate limit reached for " + category);
    }
}
