package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationMarketDataBoundaryContractTest {
    @Test
    void automationMustPreserveExistingDegradedDataFailClosedPolicy() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-market-data-boundary.md"));
        assertThat(text).contains("existing market-data freshness/degraded-data policy");
        assertThat(text).contains("do not create a new automated PAPER intent from stale/unavailable market data");
        assertThat(text).contains("do not invent a fill/exit price");
        assertThat(text).contains("market data is temporarily unavailable");
        assertThat(text).contains("must not weaken the Phase 9 degraded-data acceptance behavior");
    }
}
