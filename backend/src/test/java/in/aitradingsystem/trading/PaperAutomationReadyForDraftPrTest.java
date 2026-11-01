package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationReadyForDraftPrTest {
    @Test
    void markerRequiresDraftAndLiveSafety() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-ready-for-draft-pr.md"));
        assertThat(text).contains("Open Draft PR");
        assertThat(text).contains("inspect CI and authoritative services");
        assertThat(text).contains("Keep LIVE/broker submission disabled");
        assertThat(text).contains("keep PR Draft");
    }
}
