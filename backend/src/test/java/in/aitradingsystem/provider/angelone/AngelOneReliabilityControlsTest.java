package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AngelOneReliabilityControlsTest {

    @Test
    void verifiedProviderWindowsMatchCurrentOfficialDocumentation() {
        AngelOneRateLimiter.ProviderLimit auth = AngelOneRateLimiter.verifiedLimit(AngelOneRateLimiter.Category.AUTH);
        AngelOneRateLimiter.ProviderLimit quote = AngelOneRateLimiter.verifiedLimit(AngelOneRateLimiter.Category.MARKET_QUOTE);
        AngelOneRateLimiter.ProviderLimit candle = AngelOneRateLimiter.verifiedLimit(AngelOneRateLimiter.Category.HISTORICAL_CANDLES);

        assertThat(auth.perSecondIntervalMs()).isEqualTo(1_000);
        assertThat(auth.perMinute()).isZero();
        assertThat(auth.perHour()).isZero();
        assertThat(quote.perSecondIntervalMs()).isEqualTo(100);
        assertThat(quote.perMinute()).isEqualTo(500);
        assertThat(quote.perHour()).isEqualTo(5_000);
        assertThat(candle.perSecondIntervalMs()).isEqualTo(500);
        assertThat(candle.perMinute()).isEqualTo(80);
        assertThat(candle.perHour()).isEqualTo(5_000);
    }

    @Test
    void safeReadRetriesOnlyTransientFailuresAndRecordsRetryCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AngelOneCallMetrics metrics = new AngelOneCallMetrics(registry);
        AngelOneRetryPolicy policy = new AngelOneRetryPolicy(metrics, 3, 0, 0);
        AtomicInteger attempts = new AtomicInteger();

        String result = policy.executeSafeRead(AngelOneApiClient.LTP_PATH, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new AngelOneException(AngelOneException.Kind.UNAVAILABLE, "HTTP_503", "temporary");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(registry.get("aitrading.provider.angelone.retries")
                .tag("endpoint", AngelOneApiClient.LTP_PATH)
                .tag("reason", "unavailable")
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    void rejectedAndSessionFailuresAreNotBlindlyRetried() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AngelOneRetryPolicy policy = new AngelOneRetryPolicy(new AngelOneCallMetrics(registry), 3, 0, 0);
        AtomicInteger rejectedAttempts = new AtomicInteger();
        AtomicInteger sessionAttempts = new AtomicInteger();

        assertThatThrownBy(() -> policy.executeSafeRead(AngelOneApiClient.LTP_PATH, () -> {
            rejectedAttempts.incrementAndGet();
            throw new AngelOneException(AngelOneException.Kind.REJECTED, "AB123", "rejected");
        })).isInstanceOf(AngelOneException.class);
        assertThat(rejectedAttempts).hasValue(1);

        assertThatThrownBy(() -> policy.executeSafeRead(AngelOneApiClient.LTP_PATH, () -> {
            sessionAttempts.incrementAndGet();
            throw new AngelOneException(AngelOneException.Kind.SESSION_EXPIRED, "AG8003", "expired");
        })).isInstanceOf(AngelOneException.class);
        assertThat(sessionAttempts).hasValue(1);
    }

    @Test
    void metricsCaptureResponseLatencyThrottleAndAuthFailureWithoutHighCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AngelOneCallMetrics metrics = new AngelOneCallMetrics(registry);

        long started = metrics.start();
        metrics.recordFailure(AngelOneApiClient.CANDLE_PATH,
                new AngelOneException(AngelOneException.Kind.RATE_LIMITED, "HTTP_403", "limited"), started);
        metrics.recordFailure(AngelOneApiClient.LOGIN_PATH,
                new AngelOneException(AngelOneException.Kind.REJECTED, "HTTP_401", "rejected"), metrics.start());

        assertThat(registry.get("aitrading.provider.angelone.requests")
                .tag("endpoint", AngelOneApiClient.CANDLE_PATH)
                .tag("outcome", "rate_limited")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("aitrading.provider.angelone.latency")
                .tag("endpoint", AngelOneApiClient.CANDLE_PATH)
                .tag("outcome", "rate_limited")
                .timer().count()).isEqualTo(1L);
        assertThat(registry.get("aitrading.provider.angelone.throttled")
                .tag("endpoint", AngelOneApiClient.CANDLE_PATH)
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("aitrading.provider.angelone.auth_failures")
                .tag("type", "login_failed")
                .counter().count()).isEqualTo(1.0);
    }
}
