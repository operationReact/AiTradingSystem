package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationCiNoteContractTest {
    @Test
    void statusDoesNotClaimUnexecutedLocalValidation() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-ci-note.md"));
        assertThat(text).contains("has not executed a local Maven/Flutter build");
        assertThat(text).contains("first full compilation/test validation");
        assertThat(text).contains("Any failure is a blocker to inspect");
    }
}
