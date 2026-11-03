package in.aitradingsystem.governance;

import in.aitradingsystem.config.TradingModeGuard;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Java-authoritative environment participation policy. Configuration may enable a model environment,
 * but LIVE execution influence additionally requires the independent capital-authority gate.
 */
@Component
public class ModelEnvironmentPolicy {
    private final ModelParticipationProperties properties;
    private final TradingModeGuard tradingModeGuard;

    public ModelEnvironmentPolicy(
        ModelParticipationProperties properties,
        TradingModeGuard tradingModeGuard
    ) {
        this.properties = properties;
        this.tradingModeGuard = tradingModeGuard;
    }

    public boolean isParticipationAllowed(ModelEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        return switch (environment) {
            case RESEARCH -> properties.isResearchEnabled();
            case BACKTEST -> properties.isBacktestEnabled();
            case PAPER -> properties.isPaperEnabled();
            case SHADOW -> properties.isShadowEnabled();
            case LIVE_ADVISORY -> properties.isLiveAdvisoryEnabled();
            case LIVE_EXECUTION -> properties.isLiveExecutionInfluenceEnabled()
                && tradingModeGuard.isLiveCapitalAuthorityOpen();
        };
    }

    public void requireParticipationAllowed(ModelEnvironment environment) {
        if (!isParticipationAllowed(environment)) {
            throw new IllegalStateException(
                "Model participation is disabled for environment " + environment
            );
        }
    }
}
