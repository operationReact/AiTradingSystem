package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrChecklistContractTest {
    @Test
    void checklistKeepsIntegrationUiCiVisualDeviceWorkOpen() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-checklist.md"));
        assertThat(text).contains("[ ] Reconcile with authoritative persisted services");
        assertThat(text).contains("[ ] Implement actual Flutter mode controls/lifecycle surfaces");
        assertThat(text).contains("[ ] Run/fix exact-head required CI");
        assertThat(text).contains("[ ] Collect canonical Figma phone evidence");
        assertThat(text).contains("Current status: DRAFT / DO NOT MERGE");
    }
}
