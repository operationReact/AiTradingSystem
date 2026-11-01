package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationFailurePolicyContractTest {
    @Test
    void missingAuthorityCannotCreateNewAutomatedPaperTrade() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-failure-policy.md"));
        assertThat(text).contains("prefer no new trade over guessing");
        assertThat(text).contains("missing/expired account session");
        assertThat(text).contains("unavailable emergency-stop/risk state");
        assertThat(text).contains("duplicate/idempotency uncertainty");
        assertThat(text).contains("inability to persist an accepted order/fill/position transition");
    }
}
