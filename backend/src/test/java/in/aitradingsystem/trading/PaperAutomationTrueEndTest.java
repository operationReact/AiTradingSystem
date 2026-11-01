package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationTrueEndTest {
    @Test
    void trueEndCreateDraftPr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-true-end.md"))).contains("TRUE END. CREATE DRAFT PR");
    }
}
