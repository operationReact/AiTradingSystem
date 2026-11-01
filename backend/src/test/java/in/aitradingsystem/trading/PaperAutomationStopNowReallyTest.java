package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationStopNowReallyTest {
    @Test
    void stopNowReallyCreatePr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-stop-now-really.md"))).contains("STOP NOW REALLY. CREATE PR");
    }
}
