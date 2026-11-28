package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The capability response must satisfy the Flutter decoder's rule: an executable
 * capability has an implemented route, a connected broker, a selected broker and
 * no blockers. Otherwise the client rejects it as inconsistent.
 */
class LiveEquityCapabilityContractTest {
    private static final UUID ROOM = UUID.randomUUID();

    private static CustomerExecutionReadinessService.Readiness readiness(boolean ready, List<String> blockers) {
        return new CustomerExecutionReadinessService.Readiness(ROOM, "LIVE", "DRAFT", "ANGEL_ONE",
            true, false, true, false, false, ready, blockers, null);
    }

    @Test
    void gateOffReportsImplementedRouteButNotExecutable() {
        var c = CustomerExecutionReadinessController.capability(ROOM, readiness(false,
            List.of("EXECUTION_DISABLED")), false);
        assertThat(c.orderRouteImplemented()).isTrue();
        assertThat(c.executionEnabled()).isFalse();
        assertThat(c.blockers()).containsExactly("EXECUTION_DISABLED", "LIVE_DISPATCH_DISABLED");
    }

    @Test
    void gateOnWithBlockersIsImplementedButNotExecutable() {
        var c = CustomerExecutionReadinessController.capability(ROOM, readiness(false,
            List.of("ACCOUNT_WIDE_RISK_POLICY_NOT_CONFIGURED")), true);
        assertThat(c.orderRouteImplemented()).isTrue();
        assertThat(c.executionEnabled()).isFalse();
        assertThat(c.blockers()).containsExactly("ACCOUNT_WIDE_RISK_POLICY_NOT_CONFIGURED");
    }

    @Test
    void gateOnAndReadyIsExecutableWithNoBlockers() {
        var c = CustomerExecutionReadinessController.capability(ROOM, readiness(true, List.of()), true);
        assertThat(c.orderRouteImplemented()).isTrue();
        assertThat(c.executionEnabled()).isTrue();
        assertThat(c.blockers()).isEmpty();
    }
}
