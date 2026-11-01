package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationLastCreateFileFinalTest {
    @Test
    void createPullRequestNext() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-last-create-file-final.md")))
            .contains("CREATE_PULL_REQUEST NEXT");
    }
}
