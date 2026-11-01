package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationCiGateContractTest {
    @Test
    void ciPolicyRequiresRootCauseFixesAndProductEvidence() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-ci-gates.md"));
        assertThat(text).contains("exact PR head");
        assertThat(text).contains("Flutter analyze and full unit/widget tests");
        assertThat(text).contains("visual golden regression");
        assertThat(text).contains("Android APK packaging/delivery gate");
        assertThat(text).contains("Do not rerun an unchanged failure");
        assertThat(text).contains("CI green by itself does not satisfy phone visual or device acceptance");
    }
}
