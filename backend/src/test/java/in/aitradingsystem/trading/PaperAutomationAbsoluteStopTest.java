package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationAbsoluteStopTest {
    @Test
    void absoluteStopCreatePr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-tool-call-stop-final-final.md")))
            .contains("ABSOLUTE STOP. CREATE PR");
    }
}
