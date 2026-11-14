package in.aitradingsystem.provider.upstox;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Parses HTTP Retry-After into a bounded cooldown receipt duration.
 * An absent, malformed or excessive header must never authorize a retry.
 * This is source admission metadata only, not a provider rights grant.
 */
public final class UpstoxRetryAfterHeader {
    private static final Duration MAX = Duration.ofDays(1);

    private UpstoxRetryAfterHeader() {}

    public static Optional<Duration> parse(String header, Instant receivedAt) {
        if (header == null || receivedAt == null || header.isBlank()) return Optional.empty();
        String value = header.trim();
        try {
            if (value.matches("[0-9]+")) {
                long seconds = Long.parseLong(value);
                Duration delay = Duration.ofSeconds(seconds);
                return delay.compareTo(MAX) <= 0 ? Optional.of(delay) : Optional.empty();
            }
            Instant deadline = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(receivedAt, deadline);
            if (delay.isNegative()) delay = Duration.ZERO;
            return delay.compareTo(MAX) <= 0 ? Optional.of(delay) : Optional.empty();
        } catch (DateTimeParseException | ArithmeticException ex) {
            return Optional.empty();
        }
    }

    public static UpstoxSourceCooldownGate.Receipt receipt(String header, Instant receivedAt) {
        return new UpstoxSourceCooldownGate.Receipt(receivedAt, parse(header, receivedAt));
    }
}
