package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationSafetyInvariantContractTest {
    @Test
    void allCoreSafetyInvariantsRemainExplicit() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-safety-invariants.md"));
        assertThat(text).contains("PAPER is the only executable trading mode");
        assertThat(text).contains("LIVE availability is false");
        assertThat(text).contains("Broker submission availability is false");
        assertThat(text).contains("Emergency stop overrides both intent sources");
        assertThat(text).contains("Persisted backend state is authoritative");
        assertThat(text).contains("Angel One is read-only");
        assertThat(text).contains("CI green alone is not UI/device acceptance");
    }
}
