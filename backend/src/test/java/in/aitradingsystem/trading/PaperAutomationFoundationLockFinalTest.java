package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationFoundationLockFinalTest {
    @Test
    void finalLockRequiresDraftPr() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-trigger-lock-final.md"));
        assertThat(text).contains("STOP foundation additions. CREATE DRAFT PR");
    }
}
