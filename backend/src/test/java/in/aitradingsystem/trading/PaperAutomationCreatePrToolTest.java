package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationCreatePrToolTest {
    @Test
    void nextToolMustCreatePullRequest() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-create-pr-tool.md")))
            .contains("NEXT TOOL INVOCATION MUST BE create_pull_request");
    }
}
