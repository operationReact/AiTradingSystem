package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationNoCreateFileTest {
    @Test
    void noCreateFileCreatePullRequest() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-no-create-file.md"))).contains("NO CREATE_FILE. CREATE_PULL_REQUEST");
    }
}
