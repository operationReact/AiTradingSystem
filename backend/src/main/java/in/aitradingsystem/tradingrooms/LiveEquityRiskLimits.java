package in.aitradingsystem.tradingrooms;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deployment-configured fallback caps for LIVE cash-equity intraday orders.
 * An incomplete or out-of-range configuration is treated as absent and leaves
 * order claims fail-closed. An explicit per-customer database policy takes
 * precedence over these deployment defaults.
 */
@Component
public final class LiveEquityRiskLimits {
    private static final long MAX_INR = 10_000_000L;
    private static final int MAX_QUANTITY = 10_000;

    private final LiveEquityRiskPolicy policy;

    public LiveEquityRiskLimits(
            @Value("${MINTTICK_LIVE_EQUITY_RISK_MAX_ACCOUNT_INTRADAY_BUY_INR:0}")
                long maxAccountIntradayBuyInr,
            @Value("${MINTTICK_LIVE_EQUITY_RISK_MAX_ORDER_VALUE_INR:0}")
                long maxOrderValueInr,
            @Value("${MINTTICK_LIVE_EQUITY_RISK_MAX_ORDER_QUANTITY:0}")
                int maxOrderQuantity,
            @Value("${MINTTICK_LIVE_EQUITY_RISK_DAILY_REALIZED_LOSS_LIMIT_INR:0}")
                long dailyRealizedLossLimitInr) {
        this.policy = valid(maxAccountIntradayBuyInr, maxOrderValueInr,
                maxOrderQuantity, dailyRealizedLossLimitInr)
            ? new LiveEquityRiskPolicy(maxAccountIntradayBuyInr, maxOrderValueInr,
                maxOrderQuantity, dailyRealizedLossLimitInr)
            : null;
    }

    public static LiveEquityRiskLimits disabled() {
        return new LiveEquityRiskLimits(0, 0, 0, 0);
    }

    public Optional<LiveEquityRiskPolicy> configuredPolicy() {
        return Optional.ofNullable(policy);
    }

    private static boolean valid(long accountBuy, long orderValue, int quantity, long dailyLoss) {
        return accountBuy >= 1 && accountBuy <= MAX_INR
            && orderValue >= 1 && orderValue <= MAX_INR
            && quantity >= 1 && quantity <= MAX_QUANTITY
            && dailyLoss >= 0 && dailyLoss <= MAX_INR;
    }
}
