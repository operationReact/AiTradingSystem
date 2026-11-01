package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationLastCreateFileTest {
    @Test
    void nextCreatePullRequest() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-last-create-file.md"));
        assertThat(text).contains("THIS IS THE LAST create_file");
        assertThat(text).contains("NEXT = create_pull_request");
    }
}
