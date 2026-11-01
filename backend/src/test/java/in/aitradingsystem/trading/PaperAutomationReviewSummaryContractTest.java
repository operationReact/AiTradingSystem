package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationReviewSummaryContractTest {
    @Test
    void reviewSummaryKeepsUnfinishedReleaseGatesExplicit() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-review-summary.md"));
        assertThat(text).contains("ready for Draft PR review/CI, not merge");
        assertThat(text).contains("authoritative persistence integration");
        assertThat(text).contains("real Flutter implementation");
        assertThat(text).contains("canonical Figma phone fidelity");
        assertThat(text).contains("real-backend APK/device acceptance");
        assertThat(text).contains("LIVE/broker submission remains disabled");
    }
}
