package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrTriggerTest {
    @Test
    void draftPrFollowsThisCheckpoint() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-trigger.md"));
        assertThat(text).contains("Open the Draft PR after this commit");
    }
}
