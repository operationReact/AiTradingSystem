package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationOpenDraftNowTest {
    @Test
    void foundationCanOpenDraftButCannotMerge() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-open-draft-now.md"));
        assertThat(text).contains("Create the Draft PR now");
        assertThat(text).contains("inspect exact-head workflows");
        assertThat(text).contains("continue authoritative integration on the same branch");
        assertThat(text).contains("Do not merge");
    }
}
