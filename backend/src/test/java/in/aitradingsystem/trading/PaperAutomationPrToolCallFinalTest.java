package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrToolCallFinalTest {
    @Test
    void draftCreatePullRequestNow() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-tool-call-final.md"));
        assertThat(text).contains("create_pull_request");
        assertThat(text).contains("DRAFT=true");
        assertThat(text).contains("NOW");
    }
}
