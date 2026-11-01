package in.aitradingsystem.paper;

import in.aitradingsystem.domain.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * Server-authoritative product/risk boundary for optional PAPER trailing protection.
 *
 * <p>The Flutter client performs only structural input validation. Operational limits live here so
 * order creation, automation and future clients share one policy.
 */
@Component
public class TrailingProtectionPolicy {
    public static final BigDecimal MIN_PERCENT = new BigDecimal("0.5");
    public static final BigDecimal MAX_PERCENT = new BigDecimal("20.0");

    public BigDecimal validate(Side side, BigDecimal requestedPercent) {
        if (requestedPercent == null) return null;
        if (side != Side.BUY) {
            throw new IllegalArgumentException("Trailing protection is supported only for PAPER BUY orders");
        }
        if (requestedPercent.compareTo(MIN_PERCENT) < 0 || requestedPercent.compareTo(MAX_PERCENT) > 0) {
            throw new IllegalArgumentException("Trailing protection must be between 0.5% and 20.0%");
        }
        return requestedPercent.setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal trailingThreshold(BigDecimal highWaterMark, BigDecimal trailingPercent) {
        if (highWaterMark == null || highWaterMark.signum() <= 0) {
            throw new IllegalArgumentException("highWaterMark must be positive");
        }
        BigDecimal percent = validate(Side.BUY, trailingPercent);
        BigDecimal multiplier = BigDecimal.ONE.subtract(percent.movePointLeft(2));
        return highWaterMark.multiply(multiplier).setScale(6, RoundingMode.HALF_UP);
    }

    public BigDecimal effectiveStop(BigDecimal initialStopLoss, BigDecimal highWaterMark, BigDecimal trailingPercent) {
        BigDecimal trailing = trailingThreshold(highWaterMark, trailingPercent);
        if (initialStopLoss == null) return trailing;
        return initialStopLoss.max(trailing).setScale(6, RoundingMode.HALF_UP);
    }

    public PolicyView view() {
        return new PolicyView(MIN_PERCENT, MAX_PERCENT);
    }

    public record PolicyView(BigDecimal minPercent, BigDecimal maxPercent) {}
}
