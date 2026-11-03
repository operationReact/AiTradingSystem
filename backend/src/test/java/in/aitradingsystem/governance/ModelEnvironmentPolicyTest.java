package in.aitradingsystem.governance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.aitradingsystem.config.TradingModeGuard;
import org.junit.jupiter.api.Test;

class ModelEnvironmentPolicyTest {
    @Test
    void defaultsAllowOnlyImplementedNonLiveEnvironments() {
        ModelParticipationProperties properties = new ModelParticipationProperties();
        TradingModeGuard tradingModeGuard = mock(TradingModeGuard.class);
        ModelEnvironmentPolicy policy = new ModelEnvironmentPolicy(properties, tradingModeGuard);

        assertTrue(policy.isParticipationAllowed(ModelEnvironment.RESEARCH));
        assertTrue(policy.isParticipationAllowed(ModelEnvironment.BACKTEST));
        assertTrue(policy.isParticipationAllowed(ModelEnvironment.PAPER));
        assertFalse(policy.isParticipationAllowed(ModelEnvironment.SHADOW));
        assertFalse(policy.isParticipationAllowed(ModelEnvironment.LIVE_ADVISORY));
        assertFalse(policy.isParticipationAllowed(ModelEnvironment.LIVE_EXECUTION));
    }

    @Test
    void configuredShadowAndLiveAdvisoryRemainNonCapitalEnvironments() {
        ModelParticipationProperties properties = new ModelParticipationProperties();
        properties.setShadowEnabled(true);
        properties.setLiveAdvisoryEnabled(true);
        TradingModeGuard tradingModeGuard = mock(TradingModeGuard.class);
        ModelEnvironmentPolicy policy = new ModelEnvironmentPolicy(properties, tradingModeGuard);

        assertTrue(policy.isParticipationAllowed(ModelEnvironment.SHADOW));
        assertTrue(policy.isParticipationAllowed(ModelEnvironment.LIVE_ADVISORY));
        assertFalse(policy.isParticipationAllowed(ModelEnvironment.LIVE_EXECUTION));
    }

    @Test
    void liveExecutionFlagCannotBypassClosedJavaCapitalGate() {
        ModelParticipationProperties properties = new ModelParticipationProperties();
        properties.setLiveExecutionInfluenceEnabled(true);
        TradingModeGuard tradingModeGuard = mock(TradingModeGuard.class);
        when(tradingModeGuard.isLiveCapitalAuthorityOpen()).thenReturn(false);
        ModelEnvironmentPolicy policy = new ModelEnvironmentPolicy(properties, tradingModeGuard);

        assertFalse(policy.isParticipationAllowed(ModelEnvironment.LIVE_EXECUTION));
        assertThrows(
            IllegalStateException.class,
            () -> policy.requireParticipationAllowed(ModelEnvironment.LIVE_EXECUTION)
        );
    }

    @Test
    void liveExecutionRequiresBothParticipationAndCapitalAuthority() {
        ModelParticipationProperties properties = new ModelParticipationProperties();
        properties.setLiveExecutionInfluenceEnabled(true);
        TradingModeGuard tradingModeGuard = mock(TradingModeGuard.class);
        when(tradingModeGuard.isLiveCapitalAuthorityOpen()).thenReturn(true);
        ModelEnvironmentPolicy policy = new ModelEnvironmentPolicy(properties, tradingModeGuard);

        assertTrue(policy.isParticipationAllowed(ModelEnvironment.LIVE_EXECUTION));
    }
}
