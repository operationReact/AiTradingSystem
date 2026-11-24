package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;

/** The loaded-snapshot accessor must stay closed while discovery rights are unverified. */
class FnoFuturesLoadedSnapshotGateTest {
    @Test
    void noSnapshotIsReturnedWhileDiscoveryIsNotVerified() {
        // The fetcher is never reached when discovery is unverified, so null is safe here.
        var registry = new FnoFuturesContractRegistry(null, Clock.systemUTC(), false);
        assertThat(registry.loadedUniverseSnapshot()).isEmpty();
        assertThat(registry.universeStatus().state()).isEqualTo("DISCOVERY_NOT_VERIFIED");
    }

    @Test
    void noSnapshotIsReturnedBeforeAnyRefreshEvenWhenDiscoveryIsVerified() {
        var registry = new FnoFuturesContractRegistry(null, Clock.systemUTC(), true);
        assertThat(registry.loadedUniverseSnapshot()).isEmpty();
        assertThat(registry.universeStatus().state()).isEqualTo("NOT_LOADED");
    }
}
