package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationDoNotCallCreateFileTest {
    @Test
    void callCreatePullRequestInstead() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-do-not-call-create-file.md"));
        assertThat(text).contains("DO NOT CALL create_file AGAIN");
        assertThat(text).contains("CALL create_pull_request");
    }
}
