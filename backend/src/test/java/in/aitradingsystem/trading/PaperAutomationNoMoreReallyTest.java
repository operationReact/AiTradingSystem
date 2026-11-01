package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationNoMoreReallyTest {
    @Test
    void noMoreReallyPr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-no-more-really.md"))).contains("NO MORE REALLY. PR");
    }
}
