package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationCiFirstPassContractTest {
    @Test
    void firstCiPassRequiresRootCauseFixesAndDoesNotAuthorizeMerge() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-ci-first-pass.md"));
        assertThat(text).contains("fix the concrete source/API mismatch on a new head");
        assertThat(text).contains("do not rerun unchanged failures");
        assertThat(text).contains("do not merge even if CI is green");
    }
}
