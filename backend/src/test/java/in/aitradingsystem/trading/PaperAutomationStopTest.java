package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationStopTest {
    @Test
    void stopMeansDraftPrNext() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-final-marker-2.md")))
            .contains("CREATE DRAFT PR. NO MORE FOUNDATION FILES");
    }
}
