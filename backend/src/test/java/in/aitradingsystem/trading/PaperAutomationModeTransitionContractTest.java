package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationModeTransitionContractTest {
    @Test
    void transitionsPreserveExistingPaperSafetyAndKeepLiveClosed() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-mode-transitions.md"));
        assertThat(text).contains("PAPER + Manual -> PAPER + Automated: allowed");
        assertThat(text).contains("stops creation of new automated intents");
        assertThat(text).contains("client state alone cannot resume execution");
        assertThat(text).contains("PAPER -> LIVE: unavailable/fail closed");
        assertThat(text).contains("must not duplicate, orphan or silently convert existing PAPER orders/positions");
    }
}
