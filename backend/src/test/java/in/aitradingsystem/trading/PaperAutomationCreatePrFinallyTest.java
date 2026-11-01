package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationCreatePrFinallyTest {
    @Test
    void finallyCreateDraftPr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-create-pr-finally.md"))).contains("FINALLY CREATE DRAFT PR");
    }
}
