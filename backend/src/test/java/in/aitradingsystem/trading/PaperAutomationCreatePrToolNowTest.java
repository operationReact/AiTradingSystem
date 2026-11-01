package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationCreatePrToolNowTest {
    @Test
    void createPullRequestToolNow() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-create-pr-tool-now.md"))).contains("CREATE_PULL_REQUEST TOOL NOW");
    }
}
