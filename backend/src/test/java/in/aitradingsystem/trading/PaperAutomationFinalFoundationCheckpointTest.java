package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationFinalFoundationCheckpointTest {
    @Test
    void foundationIsCompleteWithoutClaimingReleaseCompletion() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-final-foundation-checkpoint.md"));
        assertThat(text).contains("Foundation checkpoint complete; production integration remains open");
        assertThat(text).contains("stops short of claiming the product slice complete");
        assertThat(text).contains("actual Flutter UI must be implemented");
        assertThat(text).contains("LIVE unavailable; broker submission unavailable");
        assertThat(text).contains("no production deployment performed");
    }
}
