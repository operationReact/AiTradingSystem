package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationFoundationCompleteTest {
    @Test
    void nextWorkIsIntegrationNotMoreParallelFoundation() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-foundation-complete.md"));
        assertThat(text).contains("Foundation coding checkpoint: COMPLETE");
        assertThat(text).contains("Product/release slice: IN PROGRESS");
        assertThat(text).contains("PR disposition: DRAFT ONLY");
        assertThat(text).contains("not more parallel foundation modeling");
        assertThat(text).contains("authoritative persistence integration, real Flutter implementation and evidence closure");
    }
}
