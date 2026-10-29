package in.aitradingsystem.provider.angelone;

import in.aitradingsystem.config.AngelOneProperties;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Process-local SmartAPI request throttle.
 *
 * <p>The provider windows below were verified against Angel One's official SmartAPI RateLimit documentation on
 * 2026-10-29: login 1/sec; LTP 10/sec, 500/min, 5000/hour; historical candles 2/sec, 80/min, 5000/hour.
 * Configured minimum intervals may be more conservative. A configured value of zero is retained only as an
 * explicit fixture/test bypass for the legacy constructors; production application.yml does not default to zero.
 */
@Component
public class AngelOneRateLimiter {
    public enum Category { AUTH, MARKET_QUOTE, HISTORICAL_CANDLES }

    record ProviderLimit(long perSecondIntervalMs, int perMinute, int perHour) {}

    private static final Map<Category, ProviderLimit> VERIFIED_LIMITS = Map.of(
            Category.AUTH, new ProviderLimit(1_000, 0, 0),
            Category.MARKET_QUOTE, new ProviderLimit(100, 500, 5_000),
            Category.HISTORICAL_CANDLES, new ProviderLimit(500, 80, 5_000));

    private final Map<Category, Long> minIntervalNanos = new EnumMap<>(Category.class);
    private final Map<Category, Long> lastAcquiredNanos = new EnumMap<>(Category.class);
    private final Map<Category, Deque<Long>> minuteAcquisitions = new EnumMap<>(Category.class);
    private final Map<Category, Deque<Long>> hourAcquisitions = new EnumMap<>(Category.class);
    private final long recoveryMaxWaitMs;

    public AngelOneRateLimiter(AngelOneProperties properties) {
        configure(Category.AUTH, properties.loginMinIntervalMs());
        configure(Category.MARKET_QUOTE, properties.quoteMinIntervalMs());
        configure(Category.HISTORICAL_CANDLES, 500);
        recoveryMaxWaitMs = properties.sessionRetryMaxWaitMs();
        for (Category category : Category.values()) {
            minuteAcquisitions.put(category, new ArrayDeque<>());
            hourAcquisitions.put(category, new ArrayDeque<>());
        }
    }

    private void configure(Category category, long configuredMs) {
        long effectiveMs = configuredMs == 0
                ? 0
                : Math.max(configuredMs, VERIFIED_LIMITS.get(category).perSecondIntervalMs());
        minIntervalNanos.put(category, TimeUnit.MILLISECONDS.toNanos(effectiveMs));
    }

    public synchronized void acquire(Category category) {
        long now = System.nanoTime();
        if (!allowed(category, now)) throw rateLimited(category);
        record(category, now);
    }

    public synchronized void acquireForRecovery(Category category) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(recoveryMaxWaitMs);
        while (true) {
            long now = System.nanoTime();
            if (allowed(category, now)) {
                record(category, now);
                return;
            }
            long remaining = deadline - now;
            if (remaining <= 0) throw rateLimited(category);
            long waitNanos = Math.min(remaining, waitRequiredNanos(category, now));
            try {
                TimeUnit.NANOSECONDS.timedWait(this, Math.max(1L, waitNanos));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AngelOneException(AngelOneException.Kind.UNAVAILABLE, "RECOVERY_WAIT_INTERRUPTED",
                        "Session recovery was interrupted");
            }
        }
    }

    private boolean allowed(Category category, long now) {
        prune(category, now);
        Long last = lastAcquiredNanos.get(category);
        if (last != null && now - last < minIntervalNanos.get(category)) return false;
        ProviderLimit limit = VERIFIED_LIMITS.get(category);
        return (limit.perMinute() == 0 || minuteAcquisitions.get(category).size() < limit.perMinute())
                && (limit.perHour() == 0 || hourAcquisitions.get(category).size() < limit.perHour());
    }

    private long waitRequiredNanos(Category category, long now) {
        long wait = 1L;
        Long last = lastAcquiredNanos.get(category);
        if (last != null) wait = Math.max(wait, minIntervalNanos.get(category) - (now - last));
        ProviderLimit limit = VERIFIED_LIMITS.get(category);
        if (limit.perMinute() > 0 && minuteAcquisitions.get(category).size() >= limit.perMinute()) {
            wait = Math.max(wait, minuteAcquisitions.get(category).peekFirst() + TimeUnit.MINUTES.toNanos(1) - now);
        }
        if (limit.perHour() > 0 && hourAcquisitions.get(category).size() >= limit.perHour()) {
            wait = Math.max(wait, hourAcquisitions.get(category).peekFirst() + TimeUnit.HOURS.toNanos(1) - now);
        }
        return wait;
    }

    private void record(Category category, long now) {
        lastAcquiredNanos.put(category, now);
        minuteAcquisitions.get(category).addLast(now);
        hourAcquisitions.get(category).addLast(now);
    }

    private void prune(Category category, long now) {
        prune(minuteAcquisitions.get(category), now - TimeUnit.MINUTES.toNanos(1));
        prune(hourAcquisitions.get(category), now - TimeUnit.HOURS.toNanos(1));
    }

    private static void prune(Deque<Long> values, long cutoff) {
        while (!values.isEmpty() && values.peekFirst() <= cutoff) values.removeFirst();
    }

    static ProviderLimit verifiedLimit(Category category) {
        return VERIFIED_LIMITS.get(category);
    }

    private static AngelOneException rateLimited(Category category) {
        return new AngelOneException(AngelOneException.Kind.RATE_LIMITED, "LOCAL_RATE_LIMIT",
                "Local rate limit reached for " + category);
    }
}
