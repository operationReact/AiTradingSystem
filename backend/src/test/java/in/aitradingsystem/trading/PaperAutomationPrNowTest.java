package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrNowTest {
    @Test
    void noMoreFoundationBeforeDraftPr() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-now.md"));
        assertThat(text).contains("Draft PR creation is now the next action");
        assertThat(text).contains("No further foundation mutation should precede it");
    }
}
