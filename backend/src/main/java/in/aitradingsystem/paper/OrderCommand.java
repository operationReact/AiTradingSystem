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
    ProductType productType
) {
    /** Backward-compatible PAPER simulation command: legacy callers remain DELIVERY by default. */
    public OrderCommand(String symbol, Side side, int quantity, BigDecimal entryPrice,
                        BigDecimal stopLoss, BigDecimal target, Instant signalGeneratedAt) {
        this(symbol, side, quantity, entryPrice, stopLoss, target, signalGeneratedAt, ProductType.DELIVERY);
    }

    public OrderCommand {
        if (productType == null) productType = ProductType.DELIVERY;
    }

    /** Hash of the normalized payload, used to detect Idempotency-Key reuse with different content. */
    public String canonicalHash() {
        String canonical = String.join("|",
            symbol, side.name(), Integer.toString(quantity), productType.name(),
            normalize(entryPrice), normalize(stopLoss), normalize(target),
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
