package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrNextFinalTest {
    @Test
    void prNextFinal() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-next-final.md"))).contains("PR NEXT FINAL");
    }
}
