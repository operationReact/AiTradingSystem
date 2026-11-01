package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrGoFinalTest {
    @Test
    void goNow() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-go-final.md"))).contains("GO NOW");
    }
}
