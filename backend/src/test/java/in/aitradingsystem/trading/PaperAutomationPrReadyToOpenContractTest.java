package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrReadyToOpenContractTest {
    @Test
    void openingDraftDoesNotImplyMergePhaseOrLiveReadiness() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-ready-to-open.md"));
        assertThat(text).contains("Ready to open Draft PR: YES");
        assertThat(text).contains("Ready to merge: NO");
        assertThat(text).contains("Phase complete: NO");
        assertThat(text).contains("LIVE broker submission enabled: NO");
        assertThat(text).contains("Production deployment authorized: NO");
    }
}
