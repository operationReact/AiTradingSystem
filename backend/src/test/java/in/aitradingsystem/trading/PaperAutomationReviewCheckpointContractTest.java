package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationReviewCheckpointContractTest {
    @Test
    void checkpointCannotBeTreatedAsMergeReady() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-review-checkpoint.md"));
        assertThat(text).contains("FOUNDATION IMPLEMENTED / INTEGRATION PENDING / DRAFT ONLY");
        assertThat(text).contains("not been merged");
        assertThat(text).contains("no duplicate production ledger was introduced");
        assertThat(text).contains("Flutter screens implement, not merely document");
        assertThat(text).contains("LIVE and broker submission remain impossible");
    }
}
