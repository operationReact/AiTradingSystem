package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationLastOneTest {
    @Test
    void lastOnePr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-last-one.md"))).contains("LAST ONE. PR");
    }
}
