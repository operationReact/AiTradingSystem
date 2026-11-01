package in.aitradingsystem.trading;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Account-scoped preference boundary. This is intentionally an in-process
 * adapter until wired to the existing persisted account preference store.
 * It always defaults fail-safe to PAPER + MANUAL and cannot enable broker
 * submission.
 */
@Service
public class AccountTradingPreferenceService {
    private final Map<String, AccountTradingPreference> preferences = new ConcurrentHashMap<>();

    public AccountTradingPreference get(String accountId) {
        requireAccount(accountId);
        return preferences.getOrDefault(accountId, AccountTradingPreference.safeDefault(accountId));
    }

    public AccountTradingPreference setExecutionMode(String accountId, ExecutionMode executionMode) {
        requireAccount(accountId);
        if (executionMode == null) throw new IllegalArgumentException("Execution mode is required.");
        var current = get(accountId);
        var updated = new AccountTradingPreference(accountId, current.tradingMode(), executionMode);
        preferences.put(accountId, updated);
        return updated;
    }

    public AccountTradingPreference setTradingMode(String accountId, TradingMode tradingMode) {
        requireAccount(accountId);
        if (tradingMode == null) throw new IllegalArgumentException("Trading mode is required.");
        if (tradingMode == TradingMode.LIVE) {
            throw new IllegalStateException("Live trading is not available yet. PAPER mode remains active.");
        }
        var current = get(accountId);
        var updated = new AccountTradingPreference(accountId, TradingMode.PAPER, current.executionMode());
        preferences.put(accountId, updated);
        return updated;
    }

    private void requireAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("Account is required.");
    }
}
