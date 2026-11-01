package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrTriggerFinalTest {
    @Test
    void noMoreFoundationBeforeDraftPr() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-trigger-final.md"));
        assertThat(text).contains("No more foundation commits. Open Draft PR now");
    }
}
