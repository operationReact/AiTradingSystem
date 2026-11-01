package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationCreatePrToolFinalTest {
    @Test
    void nextIsCreatePullRequestNotCreateFile() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-create-pr-tool-final.md"));
        assertThat(text).contains("create_pull_request NEXT");
        assertThat(text).contains("NO create_file");
    }
}
