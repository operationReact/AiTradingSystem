package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationOpenPrCommandTest {
    @Test
    void prCreationIntentIsDraftOnly() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-open-pr-command.md"));
        assertThat(text).contains("create a Draft pull request");
        assertThat(text).contains("product/paper-automation-risk-controls");
        assertThat(text).contains("to `dev`");
        assertThat(text).contains("Do not mark Ready and do not merge");
    }
}
