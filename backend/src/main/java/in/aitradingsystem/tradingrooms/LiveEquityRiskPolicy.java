package in.aitradingsystem.tradingrooms;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Account-wide (all rooms) LIVE intraday cash-equity risk caps and a pure
 * assessment that is applied at claim time, on top of the per-room budget and
 * broker funds gates. Every input that cannot be read fails the claim closed;
 * nothing here is ever defaulted to a permissive value.
 *
 * @param maxAccountIntradayBuyInr   cumulative same-day BUY notional allowed
 *                                   across ALL of the customer's LIVE rooms
 * @param maxOrderValueInr           largest single-order notional allowed
 * @param maxOrderQuantity           largest single-order quantity allowed
 * @param dailyRealizedLossLimitInr  realized intraday loss at/after which new
 *                                   orders are blocked
 */
public record LiveEquityRiskPolicy(long maxAccountIntradayBuyInr, long maxOrderValueInr,
        int maxOrderQuantity, long dailyRealizedLossLimitInr) {

    /**
     * Assess one proposed order against account-wide caps. Returns the blocker
     * codes that apply; an empty list means this gate is satisfied (it is never
     * the only gate — EXECUTION_DISABLED and the per-room/funds gates still apply).
     *
     * @param accountWideBuyNotionalToday cumulative same-day BUY notional across
     *        all the customer's LIVE rooms, or {@code null} if it could not be read
     * @param todayRealizedLossInr realized intraday loss so far, or {@code null}
     *        if it could not be read (fails closed)
     * @param policy the durable account policy, or {@code null} if absent/unreadable
     */
    public static List<String> assessClaimRisk(String side, BigDecimal notional,
            int quantity, BigDecimal accountWideBuyNotionalToday,
            BigDecimal todayRealizedLossInr, LiveEquityRiskPolicy policy) {
        List<String> blockers = new ArrayList<>();
        if (policy == null) {
            blockers.add("ACCOUNT_WIDE_RISK_POLICY_NOT_CONFIGURED");
            return List.copyOf(blockers);
        }
        if (notional == null || quantity < 1) {
            blockers.add("ORDER_RISK_INPUT_INVALID");
            return List.copyOf(blockers);
        }
        if (quantity > policy.maxOrderQuantity()) {
            blockers.add("ORDER_EXCEEDS_MAX_QUANTITY");
        }
        if (notional.compareTo(BigDecimal.valueOf(policy.maxOrderValueInr())) > 0) {
            blockers.add("ORDER_EXCEEDS_MAX_ORDER_VALUE");
        }
        if (todayRealizedLossInr == null) {
            blockers.add("DAILY_REALIZED_LOSS_NOT_VERIFIED");
        } else if (todayRealizedLossInr.compareTo(
                BigDecimal.valueOf(policy.dailyRealizedLossLimitInr())) >= 0) {
            blockers.add("DAILY_REALIZED_LOSS_LIMIT_REACHED");
        }
        if ("BUY".equals(side)) {
            if (accountWideBuyNotionalToday == null) {
                blockers.add("ACCOUNT_WIDE_INTRADAY_EXPOSURE_NOT_VERIFIED");
            } else if (accountWideBuyNotionalToday.add(notional).compareTo(
                    BigDecimal.valueOf(policy.maxAccountIntradayBuyInr())) > 0) {
                blockers.add("ORDER_EXCEEDS_ACCOUNT_WIDE_INTRADAY_BUDGET");
            }
        }
        return List.copyOf(blockers);
    }
}
