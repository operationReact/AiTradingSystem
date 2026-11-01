package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationToolLockTest {
    @Test
    void toolLockIsCreatePullRequest() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-tool-lock.md"))).contains("TOOL LOCK: create_pull_request");
    }
}
