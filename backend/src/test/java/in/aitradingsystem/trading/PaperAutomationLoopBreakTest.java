package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationLoopBreakTest {
    @Test
    void breakLoopCreatePullRequest() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-loop-break.md"))).contains("BREAK LOOP. CREATE PULL REQUEST");
    }
}
