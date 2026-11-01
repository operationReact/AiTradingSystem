package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationFinalLockTest {
    @Test
    void finalLockPr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-final-lock.md"))).contains("FINAL LOCK. PR");
    }
}
