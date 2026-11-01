package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationLiveBoundaryContractTest {
    @Test
    void currentSliceCannotBeInterpretedAsLiveApproval() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-live-boundary.md"));
        assertThat(text).contains("`liveAvailable = false`");
        assertThat(text).contains("`brokerSubmissionAvailable = false`");
        assertThat(text).contains("PAPER simulation never imports or invokes Angel One order submission");
        assertThat(text).contains("Nothing in this branch constitutes that approval");
    }
}
