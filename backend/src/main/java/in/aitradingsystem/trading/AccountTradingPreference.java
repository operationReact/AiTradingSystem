package in.aitradingsystem.trading;

public record AccountTradingPreference(
    String accountId,
    TradingMode tradingMode,
    ExecutionMode executionMode
) {
    public static AccountTradingPreference safeDefault(String accountId) {
        return new AccountTradingPreference(accountId, TradingMode.PAPER, ExecutionMode.MANUAL);
    }
}
