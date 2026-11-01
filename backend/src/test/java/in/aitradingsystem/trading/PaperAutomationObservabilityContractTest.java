package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationObservabilityContractTest {
    @Test
    void observabilityContractProtectsCredentialsAndCustomerCopy() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-observability.md"));
        assertThat(text).contains("Do not log broker credentials, access tokens, session credentials");
        assertThat(text).contains("Do not surface raw event names or internal rejection codes");
        assertThat(text).contains("LIVE broker submission is absent");
    }
}
