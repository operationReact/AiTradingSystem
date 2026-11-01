package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationOpenPrDecisionContractTest {
    @Test
    void openingDraftStartsCiWithoutWaivingMergeBlockers() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-open-pr-now.md"));
        assertThat(text).contains("open the PR now as Draft");
        assertThat(text).contains("does not waive any merge blocker");
        assertThat(text).contains("Fix concrete failures at source");
        assertThat(text).contains("integrate rather than extending temporary in-process state");
    }
}
