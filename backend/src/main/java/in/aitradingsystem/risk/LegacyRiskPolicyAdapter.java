package in.aitradingsystem.risk;

import in.aitradingsystem.config.RiskProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Compatibility-only adapter used while RiskInput/RiskPolicy remain the production contract.
 * It creates a minimum RiskContext with explicit bootstrap defaults; production switching is deferred.
 */
public final class LegacyRiskPolicyAdapter {
    private static final String LEGACY_CORRELATION = "legacy-risk-v1-shadow";
    private static final String LEGACY_EXCHANGE = "LEGACY";
    private static final String LEGACY_SYMBOL = "LEGACY";
    private static final String LEGACY_STRATEGY = "LEGACY_RISK_POLICY_V1";
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal UNBOUNDED_NOTIONAL = new BigDecimal("999999999999999.99");

    private final RiskProperties properties;
    private final RiskPolicySnapshot bootstrapPolicy;

    public LegacyRiskPolicyAdapter(RiskProperties properties) {
        if (properties == null) throw new IllegalArgumentException("risk properties are required");
        this.properties = properties;
        this.bootstrapPolicy = bootstrapPolicy(properties);
    }

    public RiskContext adapt(RiskInput input) {
        if (input == null) throw new IllegalArgumentException("risk input is required");
        BigDecimal heldMarketValue = input.referencePrice().multiply(BigDecimal.valueOf(input.heldQuantity()));
        BigDecimal currentEquity = input.startingCapital().add(input.realizedPnlToday());
        UUID orderId = UUID.nameUUIDFromBytes(canonicalInput(input).getBytes(StandardCharsets.UTF_8));

        return new RiskContext(
                orderId,
                LEGACY_CORRELATION,
                ExecutionMode.PAPER,
                input.side(),
                1L,
                LEGACY_EXCHANGE,
                LEGACY_SYMBOL,
                LEGACY_STRATEGY,
                TradingStyle.INTRADAY,
                input.quantity(),
                input.referencePrice(),
                input.stopLoss(),
                input.targetPrice(),
                input.signalGeneratedAt(),
                input.now(),
                input.now(),
                input.startingCapital(),
                input.availableCash(),
                currentEquity,
                input.realizedPnlToday(),
                input.heldQuantity(),
                heldMarketValue,
                input.openPositionCount(),
                heldMarketValue,
                heldMarketValue,
                0,
                false,
                input.newOrdersHalted(),
                MarketSessionState.OPEN,
                true,
                bootstrapPolicy);
    }

    /** Test/shadow-only inverse for verifying that the compatibility migration preserves V1 inputs. */
    public RiskInput toLegacyInput(RiskContext context) {
        if (context == null) throw new IllegalArgumentException("risk context is required");
        return new RiskInput(
                context.side(),
                context.quantity(),
                context.referencePrice(),
                context.stopLoss(),
                context.targetPrice(),
                context.signalGeneratedAt(),
                context.now(),
                context.startingCapital(),
                context.availableCash(),
                context.heldQuantity(),
                context.openPositionCount(),
                context.realizedPnlToday(),
                context.emergencyStopActive());
    }

    public RiskPolicySnapshot bootstrapPolicy() {
        return bootstrapPolicy;
    }

    private static RiskPolicySnapshot bootstrapPolicy(RiskProperties properties) {
        String canonical = String.join("|",
                RiskPolicy.VERSION,
                properties.riskPerTradePercent().toPlainString(),
                properties.maxDailyDrawdownPercent().toPlainString(),
                Integer.toString(properties.maxOpenPositions()),
                properties.feeRate().toPlainString(),
                properties.slippageRate().toPlainString(),
                Integer.toString(properties.signalMaxAgeMinutes()));
        UUID id = UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
        return new RiskPolicySnapshot(
                id,
                1,
                properties.riskPerTradePercent(),
                properties.maxDailyDrawdownPercent(),
                properties.maxOpenPositions(),
                BigDecimal.ZERO,
                UNBOUNDED_NOTIONAL,
                HUNDRED,
                HUNDRED,
                Integer.MAX_VALUE,
                properties.signalMaxAgeMinutes(),
                300,
                false,
                true,
                false,
                properties.feeRate(),
                properties.slippageRate(),
                "legacy-risk-v1-bootstrap");
    }

    private static String canonicalInput(RiskInput input) {
        return String.join("|",
                input.side().name(),
                Integer.toString(input.quantity()),
                input.referencePrice().stripTrailingZeros().toPlainString(),
                value(input.stopLoss()),
                value(input.targetPrice()),
                input.signalGeneratedAt() == null ? "" : input.signalGeneratedAt().toString(),
                input.now().toString(),
                input.startingCapital().stripTrailingZeros().toPlainString(),
                input.availableCash().stripTrailingZeros().toPlainString(),
                Integer.toString(input.heldQuantity()),
                Integer.toString(input.openPositionCount()),
                input.realizedPnlToday().stripTrailingZeros().toPlainString(),
                Boolean.toString(input.newOrdersHalted()));
    }

    private static String value(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
