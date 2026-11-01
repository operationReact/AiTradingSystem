package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationNoMoreTest {
    @Test
    void openDraftPr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-no-more.md")))
            .contains("OPEN DRAFT PR");
    }
}
