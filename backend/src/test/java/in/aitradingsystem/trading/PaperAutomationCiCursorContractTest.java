package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationCiCursorContractTest {
    @Test
    void ciCursorUsesOnlyCurrentExactHead() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-ci-cursor.md"));
        assertThat(text).contains("exact PR head");
        assertThat(text).contains("failed jobs as concrete blockers to inspect and fix");
        assertThat(text).contains("green as compilation/test evidence only, not merge authorization");
        assertThat(text).contains("Do not use workflow evidence from PR #206 or an earlier head");
    }
}
