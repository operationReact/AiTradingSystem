package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationMergePolicyContractTest {
    @Test
    void mergeRequiresOneAcceptedExactHead() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-merge-policy.md"));
        assertThat(text).contains("Keep the PR Draft");
        assertThat(text).contains("same exact head");
        assertThat(text).contains("no duplicate ledger");
        assertThat(text).contains("phone-size canonical Figma evidence accepted");
        assertThat(text).contains("LIVE/broker submission proven disabled");
        assertThat(text).contains("Do not merge based on stale CI from an earlier SHA");
    }
}
