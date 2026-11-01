package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationTestMatrixContractTest {
    @Test
    void matrixCoversSafetyLifecycleAndUiAcceptance() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-test-matrix.md"));
        assertThat(text).contains("New account", "Duplicate manual/AI intent", "Emergency stop before intent",
            "Target reached", "Stop reached", "Request LIVE mode", "Phone visual acceptance");
    }
}
