package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationSummaryContractTest {
    @Test
    void summaryKeepsFoundationAndReleaseAcceptanceDistinct() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-summary.md"));
        assertThat(text).contains("PAPER/LIVE × Manual/Automated");
        assertThat(text).contains("LIVE unavailable, broker submission unavailable");
        assertThat(text).contains("idempotent simulated fills");
        assertThat(text).contains("not yet merge-ready production integration");
        assertThat(text).contains("actual Flutter controls/lifecycle UI");
    }
}
