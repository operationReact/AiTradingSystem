package in.aitradingsystem.paper;

import in.aitradingsystem.domain.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Transparent paper-fill assumptions: adverse slippage on the reference price and a flat fee per side. */
public final class PaperExecutionModel {
    private PaperExecutionModel() {}

    public static BigDecimal fillPrice(Side side, BigDecimal referencePrice, BigDecimal slippageRate) {
        BigDecimal factor = side == Side.BUY
            ? BigDecimal.ONE.add(slippageRate)
            : BigDecimal.ONE.subtract(slippageRate);
        return referencePrice.multiply(factor).setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal fee(BigDecimal gross, BigDecimal feeRate) {
        return gross.multiply(feeRate).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}