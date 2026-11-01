package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationFinalDraftMarkerTest {
    @Test
    void finalMarkerRequiresOpenDraftPr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-final-marker-4.md")))
            .contains("OPEN DRAFT PR");
    }
}
