package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationLoopBrokenTest {
    @Test
    void nextCallIsCreatePullRequest() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-loop-broken.md"))).contains("NEXT CALL IS create_pull_request");
    }
}
