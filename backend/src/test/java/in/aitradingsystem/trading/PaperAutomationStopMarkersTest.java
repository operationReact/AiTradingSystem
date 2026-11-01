package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationStopMarkersTest {
    @Test
    void stopMarkersCreatePr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-this-is-ridiculous.md"))).contains("STOP MARKERS. CREATE PR");
    }
}
