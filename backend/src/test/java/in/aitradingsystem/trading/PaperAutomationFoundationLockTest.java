package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationFoundationLockTest {
    @Test
    void foundationLockPointsToDraftPr() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-trigger-lock.md"));
        assertThat(text).contains("LOCKED. Next action: Draft PR creation");
    }
}
