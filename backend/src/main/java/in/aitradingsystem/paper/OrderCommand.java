package in.aitradingsystem.paper;

import in.aitradingsystem.domain.ProductType;
import in.aitradingsystem.domain.Side;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

public record OrderCommand(
    String symbol,
    Side side,
    int quantity,
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal target,
    Instant signalGeneratedAt,
    ProductType productType,
    BigDecimal trailingStopPercent,
    String executionOrigin,
    String lifecycleReason
) {
    /** Backward-compatible PAPER simulation command: legacy callers remain DELIVERY with trailing disabled. */
    public OrderCommand(String symbol, Side side, int quantity, BigDecimal entryPrice,
                        BigDecimal stopLoss, BigDecimal target, Instant signalGeneratedAt) {
        this(symbol, side, quantity, entryPrice, stopLoss, target, signalGeneratedAt, ProductType.DELIVERY, null,
            "MANUAL", "CUSTOMER_ORDER");
    }

    /** Backward-compatible explicit product-type command with trailing disabled. */
    public OrderCommand(String symbol, Side side, int quantity, BigDecimal entryPrice,
                        BigDecimal stopLoss, BigDecimal target, Instant signalGeneratedAt, ProductType productType) {
        this(symbol, side, quantity, entryPrice, stopLoss, target, signalGeneratedAt, productType, null,
            "MANUAL", "CUSTOMER_ORDER");
    }

    /** Backward-compatible explicit trailing command with customer/manual origin. */
    public OrderCommand(String symbol, Side side, int quantity, BigDecimal entryPrice,
                        BigDecimal stopLoss, BigDecimal target, Instant signalGeneratedAt,
                        ProductType productType, BigDecimal trailingStopPercent) {
        this(symbol, side, quantity, entryPrice, stopLoss, target, signalGeneratedAt, productType,
            trailingStopPercent, "MANUAL", "CUSTOMER_ORDER");
    }

    public OrderCommand {
        if (productType == null) productType = ProductType.DELIVERY;
        executionOrigin = normalizeOrigin(executionOrigin);
        lifecycleReason = normalizeReason(lifecycleReason);
    }

    /** Hash of the normalized payload, used to detect Idempotency-Key reuse with different content. */
    public String canonicalHash() {
        String canonical = String.join("|",
            symbol, side.name(), Integer.toString(quantity), productType.name(),
            normalize(entryPrice), normalize(stopLoss), normalize(target), normalize(trailingStopPercent),
            executionOrigin, lifecycleReason == null ? "" : lifecycleReason,
            signalGeneratedAt == null ? "" : signalGeneratedAt.toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalizeOrigin(String value) {
        String normalized = value == null ? "MANUAL" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.equals("MANUAL") && !normalized.equals("AUTOMATED")) {
            throw new IllegalArgumentException("executionOrigin must be MANUAL or AUTOMATED");
        }
        return normalized;
    }

    private static String normalizeReason(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "CUSTOMER_ORDER", "STRATEGY_SIGNAL", "STOP_LOSS", "TARGET", "TRAILING_STOP",
                 "INTRADAY_CUTOFF" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported lifecycleReason: " + normalized);
        };
    }

    private static String normalize(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
