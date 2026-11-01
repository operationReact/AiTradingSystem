package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationOpenPrTest {
    @Test
    void actionOpensDraftAndKeepsMergeBlocked() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-open-pr.md"));
        assertThat(text).contains("Open Draft PR now");
        assertThat(text).contains("into `dev`");
        assertThat(text).contains("inspect exact-head workflows and continue integration");
        assertThat(text).contains("Do not mark Ready or merge");
    }
}
