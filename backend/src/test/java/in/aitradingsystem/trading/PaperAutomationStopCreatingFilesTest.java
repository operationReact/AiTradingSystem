package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationStopCreatingFilesTest {
    @Test
    void stopCreatingFilesCreatePr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-stop-creating-files.md"))).contains("STOP CREATING FILES. CREATE PR");
    }
}
