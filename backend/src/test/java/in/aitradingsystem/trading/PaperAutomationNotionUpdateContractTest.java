package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationNotionUpdateContractTest {
    @Test
    void checkpointDoesNotOverclaimProductionIntegration() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-notion-update-draft.md"));
        assertThat(text).contains("IN PROGRESS");
        assertThat(text).contains("not merge-ready production integration");
        assertThat(text).contains("actual Flutter controls/lifecycle screens");
        assertThat(text).contains("Keep Draft");
        assertThat(text).contains("no LIVE broker submission");
    }
}
