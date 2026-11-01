package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationAuthBoundaryContractTest {
    @Test
    void productionAutomationCannotDependOnDevelopmentBootstrap() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-auth-boundary.md"));
        assertThat(text).contains("real account-scoped authentication");
        assertThat(text).contains("development-only session bootstrap is not acceptable");
        assertThat(text).contains("authorization + CSRF protections");
        assertThat(text).contains("logout/session expiry immediately prevents new Manual/Automated trade intents");
    }
}
