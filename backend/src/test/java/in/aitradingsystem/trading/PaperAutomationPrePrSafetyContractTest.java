package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrePrSafetyContractTest {
    @Test
    void prePrSafetyKeepsBranchDraftAndNonDeploying() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pre-pr-check.md"));
        assertThat(text).contains("LIVE/broker submission remains false");
        assertThat(text).contains("No deployment workflow was modified");
        assertThat(text).contains("No production/cloud mutation was performed");
        assertThat(text).contains("PR will be Draft");
        assertThat(text).contains("Merge remains blocked");
    }
}
