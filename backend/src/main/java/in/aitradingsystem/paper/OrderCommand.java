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
    BigDecimal trailingPercent
) {
    /** Backward-compatible PAPER simulation command: legacy callers remain DELIVERY with no trailing protection. */
    public OrderCommand(String symbol, Side side, int quantity, BigDecimal entryPrice,
                        BigDecimal stopLoss, BigDecimal target, Instant signalGeneratedAt) {
        this(symbol, side, quantity, entryPrice, stopLoss, target, signalGeneratedAt, ProductType.DELIVERY, null);
    }

    /** Backward-compatible explicit product command with trailing protection disabled. */
    public OrderCommand(String symbol, Side side, int quantity, BigDecimal entryPrice,
                        BigDecimal stopLoss, BigDecimal target, Instant signalGeneratedAt,
                        ProductType productType) {
        this(symbol, side, quantity, entryPrice, stopLoss, target, signalGeneratedAt, productType, null);
    }

    public OrderCommand {
        if (productType == null) productType = ProductType.DELIVERY;
        if (trailingPercent != null) {
            if (side != Side.BUY) {
                throw new IllegalArgumentException("Trailing protection is supported only for PAPER BUY positions");
            }
            if (trailingPercent.signum() <= 0 || trailingPercent.compareTo(new BigDecimal("100")) >= 0) {
                throw new IllegalArgumentException("trailingPercent must be greater than 0 and less than 100");
            }
        }
    }

    /** Hash of the normalized payload, used to detect Idempotency-Key reuse with different content. */
    public String canonicalHash() {
        String canonical = String.join("|",
            symbol, side.name(), Integer.toString(quantity), productType.name(),
            normalize(entryPrice), normalize(stopLoss), normalize(target), normalize(trailingPercent),
            signalGeneratedAt == null ? "" : signalGeneratedAt.toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
