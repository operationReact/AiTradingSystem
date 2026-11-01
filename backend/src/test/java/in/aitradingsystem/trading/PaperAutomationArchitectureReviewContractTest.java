package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationArchitectureReviewContractTest {
    @Test
    void reviewRequiresAuthoritativePersistenceAndActualFlutterUiBeforeMerge() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-review-notes.md"));
        assertThat(text).contains("not acceptable as a second production ledger");
        assertThat(text).contains("integrate/refactor against those existing authoritative services");
        assertThat(text).contains("Documentation/copy contracts are not UI implementation");
        assertThat(text).contains("PR must remain Draft");
    }
}
