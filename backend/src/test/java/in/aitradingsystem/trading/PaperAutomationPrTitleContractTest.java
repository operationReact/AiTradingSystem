package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrTitleContractTest {
    @Test
    void titleDescribesFoundationRatherThanCompletedRelease() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-title.md")).trim();
        assertThat(text).isEqualTo("PAPER automation: shared risk controls and lifecycle foundation");
    }
}
