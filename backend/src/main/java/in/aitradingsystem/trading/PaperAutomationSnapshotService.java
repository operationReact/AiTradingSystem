package in.aitradingsystem.trading;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class PaperAutomationSnapshotService {
    private final AccountTradingPreferenceService preferences;

    public PaperAutomationSnapshotService(AccountTradingPreferenceService preferences) {
        this.preferences = preferences;
    }

    public PaperAutomationSnapshot snapshot(String accountId, boolean emergencyStopActive,
                                            BigDecimal unrealizedPnl, BigDecimal realizedPnl) {
        var preference = preferences.get(accountId);
        return new PaperAutomationSnapshot(preference.tradingMode(), preference.executionMode(), false,
            emergencyStopActive, emergencyStopActive ? "Trading paused" : "Risk protected",
            zeroIfNull(unrealizedPnl), zeroIfNull(realizedPnl));
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
