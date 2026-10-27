package in.aitradingsystem.provider.angelone;

import in.aitradingsystem.config.AngelOneProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Central per-category throttle. Fails fast instead of queuing so that a runaway caller cannot exceed the quota.
 */
@Component
public class AngelOneRateLimiter {

    public enum Category { AUTH, MARKET_QUOTE }

    private final Map<Category, Long> minIntervalNanos = new EnumMap<>(Category.class);
    private final Map<Category, Long> lastAcquiredNanos = new EnumMap<>(Category.class);

    public AngelOneRateLimiter(AngelOneProperties properties) {
        minIntervalNanos.put(Category.AUTH, properties.loginMinIntervalMs() * 1_000_000L);
        minIntervalNanos.put(Category.MARKET_QUOTE, properties.quoteMinIntervalMs() * 1_000_000L);
    }

    public synchronized void acquire(Category category) {
        long now = System.nanoTime();
        Long last = lastAcquiredNanos.get(category);
        if (last != null && now - last < minIntervalNanos.get(category)) {
            throw new AngelOneException(AngelOneException.Kind.RATE_LIMITED, "LOCAL_RATE_LIMIT",
                    "Local rate limit reached for " + category);
        }
        lastAcquiredNanos.put(category, now);
    }
}
