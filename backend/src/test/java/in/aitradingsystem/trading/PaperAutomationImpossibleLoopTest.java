package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationImpossibleLoopTest {
    @Test
    void terminateLoopCreatePr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-impossible-loop.md"))).contains("TERMINATE LOOP: CREATE PR");
    }
}
