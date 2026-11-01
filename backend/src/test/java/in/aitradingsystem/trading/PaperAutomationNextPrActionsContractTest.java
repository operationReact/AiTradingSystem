package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationNextPrActionsContractTest {
    @Test
    void nextActionsKeepDraftAndIntegrationFirst() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-next-pr-actions.md"));
        assertThat(text).contains("Open this branch as Draft against `dev`");
        assertThat(text).contains("Inspect current authoritative persisted services");
        assertThat(text).contains("Implement actual Flutter mode controls and lifecycle state");
        assertThat(text).contains("Fix exact-head CI failures at source");
        assertThat(text).contains("Safety remains PAPER-only");
    }
}
