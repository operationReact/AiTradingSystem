package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationDefinitionOfDoneTest {
    @Test
    void definitionOfDoneRequiresPersistenceUiVisualApkAndLiveSafety() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-definition-of-done.md"));
        assertThat(text).contains("existing persisted account preference infrastructure");
        assertThat(text).contains("one authoritative risk/execution path");
        assertThat(text).contains("phone-size visual evidence");
        assertThat(text).contains("Android APK packaging is green");
        assertThat(text).contains("LIVE availability=false and broker submission=false");
        assertThat(text).contains("keep the PR Draft");
    }
}
