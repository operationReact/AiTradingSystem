package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrOpeningStateTest {
    @Test
    void prTargetsDevAndRemainsDraft() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-opening-state.md"));
        assertThat(text).contains("target `dev`");
        assertThat(text).contains("PAPER automation: shared risk controls and lifecycle foundation");
        assertThat(text).contains("remain Draft");
        assertThat(text).contains("only valid starting CI cursor");
    }
}
