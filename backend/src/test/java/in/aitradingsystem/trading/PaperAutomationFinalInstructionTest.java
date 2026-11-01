package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationFinalInstructionTest {
    @Test
    void useCreatePullRequestNext() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-final-instruction.md"))).contains("USE create_pull_request NEXT");
    }
}
