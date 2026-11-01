package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationImplementationOrderContractTest {
    @Test
    void remainingWorkPrioritizesAuthorityThenUiThenBrokerReadOnlyAndAcceptance() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-implementation-order.md"));
        assertThat(text).contains("Authoritative backend integration");
        assertThat(text).contains("Persisted automated opportunity processing");
        assertThat(text).contains("Secure Sign In/account-scoped client session integration");
        assertThat(text).contains("Flutter account mode controls");
        assertThat(text).contains("Angel One connect/read-only");
        assertThat(text).contains("Canonical Figma phone review");
        assertThat(text).contains("PAPER-only broker-disabled safety");
    }
}
