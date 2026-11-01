package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationFinalFinalTest {
    @Test
    void createDraftPr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-final-final.md")))
            .contains("CREATE DRAFT PR");
    }
}
