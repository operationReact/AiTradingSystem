package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationLastMarkerTest {
    @Test
    void prIsNext() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-final-marker-6.md"))).contains("PR");
    }
}
