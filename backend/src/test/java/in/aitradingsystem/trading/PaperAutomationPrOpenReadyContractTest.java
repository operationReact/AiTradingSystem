package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrOpenReadyContractTest {
    @Test
    void branchCanOpenDraftWithoutBeingMergeReady() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-open-ready.md"));
        assertThat(text).contains("open a Draft PR for CI/review");
        assertThat(text).contains("does not mean it is ready to merge");
        assertThat(text).contains("expose exact-head CI failures early");
        assertThat(text).contains("Keep Draft until persistence integration, actual Flutter UI");
    }
}
