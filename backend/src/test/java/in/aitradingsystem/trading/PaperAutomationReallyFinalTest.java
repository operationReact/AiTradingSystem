package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationReallyFinalTest {
    @Test
    void reallyFinalPr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-really-final.md"))).contains("REALLY FINAL. PR");
    }
}
