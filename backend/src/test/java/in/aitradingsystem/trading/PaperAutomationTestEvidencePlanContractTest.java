package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationTestEvidencePlanContractTest {
    @Test
    void evidencePlanRequiresUnitIntegrationE2eFlutterConnectedVisualAndDeviceLayers() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-test-evidence-plan.md"));
        assertThat(text).contains("Unit tests");
        assertThat(text).contains("Service integration tests");
        assertThat(text).contains("End-to-end backend tests");
        assertThat(text).contains("Flutter widget tests");
        assertThat(text).contains("Connected runtime");
        assertThat(text).contains("Visual acceptance");
        assertThat(text).contains("Android packaging/device");
    }
}
