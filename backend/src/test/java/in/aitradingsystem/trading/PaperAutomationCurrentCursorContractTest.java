package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationCurrentCursorContractTest {
    @Test
    void cursorPrioritizesAuthoritativeIntegrationBeforeMerge() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-current-cursor.md"));
        assertThat(text).contains("integration checkpoint, not a merge-ready release");
        assertThat(text).contains("authoritative persisted Phase 10 account-preference/session code");
        assertThat(text).contains("authoritative Phase 7/8/9 risk, emergency stop, PAPER order/fill/position/portfolio/history code");
        assertThat(text).contains("there is one production ledger");
        assertThat(text).contains("Keep Draft");
    }
}
