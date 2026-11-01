package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationCheckpointShaContractTest {
    @Test
    void checkpointUsesCurrentBranchHeadRatherThanPriorPrEvidence() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-checkpoint-sha.md"));
        assertThat(text).contains("authoritative checkpoint SHA is the current branch head in GitHub");
        assertThat(text).contains("Never reuse PR #206 evidence for this new slice");
    }
}
