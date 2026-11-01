package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationCreatePrNowFinalTest {
    @Test
    void createDraftPrNowPeriod() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-create-pr-now-final.md")))
            .contains("CREATE DRAFT PR NOW. PERIOD");
    }
}
