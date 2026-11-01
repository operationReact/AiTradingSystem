package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrFinalFinalFinalTest {
    @Test
    void prFinalFinalFinal() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-final-final-final.md"))).contains("PR FINAL FINAL FINAL");
    }
}
