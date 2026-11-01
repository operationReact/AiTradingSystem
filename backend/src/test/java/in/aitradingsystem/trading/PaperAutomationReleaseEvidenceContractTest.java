package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationReleaseEvidenceContractTest {
    @Test
    void evidenceLedgerRemainsIncompleteUntilPersistenceUiVisualAndApkEvidenceExists() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-release-evidence.md"));
        assertThat(text).contains("Status: NOT YET RELEASE-ACCEPTED");
        assertThat(text).contains("Integration into existing persisted production PAPER/account/risk services");
        assertThat(text).contains("Actual Flutter controls/lifecycle screens");
        assertThat(text).contains("Canonical Figma phone-size visual comparison");
        assertThat(text).contains("Android APK packaging");
    }
}
