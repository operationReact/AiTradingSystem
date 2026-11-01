package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPerformanceBoundaryContractTest {
    @Test
    void productionAutomationRequiresRetryablePersistedProcessing() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-performance-boundary.md"));
        assertThat(text).contains("asynchronous and idempotent");
        assertThat(text).contains("opportunity evaluation can be retried safely");
        assertThat(text).contains("without duplicate exits");
        assertThat(text).contains("restart/recovery rereads persisted state");
        assertThat(text).contains("not the production scheduler or persistence mechanism");
    }
}
