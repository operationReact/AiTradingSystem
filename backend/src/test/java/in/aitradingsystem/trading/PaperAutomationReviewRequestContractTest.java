package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationReviewRequestContractTest {
    @Test
    void reviewFocusesOnIntegrationAndSafetyRatherThanPrematureMerge() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-review-request.md"));
        assertThat(text).contains("Draft foundation, not as a merge candidate");
        assertThat(text).contains("existing persisted Phase 7/8/9 services");
        assertThat(text).contains("existing Phase 10 persistence/session model");
        assertThat(text).contains("competing production source of truth");
        assertThat(text).contains("identify integration/refactor work, not approval to merge");
    }
}
