package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationStopLoopForeverTest {
    @Test
    void stopLoopForeverCreatePr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-stop-loop-forever.md"))).contains("STOP LOOP FOREVER. CREATE PR");
    }
}
