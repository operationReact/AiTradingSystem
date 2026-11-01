package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrNextCallTest {
    @Test
    void nextCallCreatePullRequestNotCreateFile() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-next-call.md"));
        assertThat(text).contains("NEXT CALL create_pull_request");
        assertThat(text).contains("NOT create_file");
    }
}
