package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationAccountIsolationContractTest {
    @Test
    void productionIntegrationMustRemainAccountScoped() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-account-isolation.md"));
        assertThat(text).contains("authenticated account on the server");
        assertThat(text).contains("does not affect another account");
        assertThat(text).contains("idempotency scope includes account identity");
        assertThat(text).contains("cannot cross account boundaries");
        assertThat(text).contains("Existing Phase 10 authorization/session controls remain mandatory");
    }
}
