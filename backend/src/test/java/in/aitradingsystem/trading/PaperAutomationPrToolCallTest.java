package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrToolCallTest {
    @Test
    void callCreatePullRequestNow() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-tool-call.md")))
            .contains("CALL create_pull_request NOW");
    }
}
