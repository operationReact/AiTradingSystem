package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPhaseEntryContractTest {
    @Test
    void phaseStartsAfterCustomerMobileMergeAndKeepsLiveDisabled() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-phase-entry.md"));
        assertThat(text).contains("700d026e9265c2be48d7ecafc1ef3afc0cb20801");
        assertThat(text).contains("customer mobile release PR #206");
        assertThat(text).contains("Manual/Automated controls");
        assertThat(text).contains("LIVE broker submission remains disabled");
    }
}
