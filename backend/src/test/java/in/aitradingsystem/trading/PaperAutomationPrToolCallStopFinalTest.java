package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrToolCallStopFinalTest {
    @Test
    void noMoreWritesPrToolCall() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-tool-call-stop-final.md")))
            .contains("NO MORE WRITES. PR TOOL CALL");
    }
}
