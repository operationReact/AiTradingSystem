package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrCreationReadyTest {
    @Test
    void nextMutationIsDraftPrNotMoreFoundation() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-creation-ready.md"));
        assertThat(text).contains("Draft PR creation is the next repository mutation");
        assertThat(text).contains("No additional foundation files are required before opening");
        assertThat(text).contains("exact-head CI and authoritative-service inspection");
    }
}
