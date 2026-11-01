package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationScopeContractTest {
    @Test
    void scopeRequiresPersistenceUiVisualApkAndLiveSafetyBeforeMerge() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-scope.md"));
        assertThat(text).contains("existing persisted production PAPER/account/risk services");
        assertThat(text).contains("actual Flutter screens");
        assertThat(text).contains("exact-head backend and Flutter gates are green");
        assertThat(text).contains("phone-size visual evidence");
        assertThat(text).contains("Android APK packages successfully");
        assertThat(text).contains("LIVE broker submission remains disabled");
    }
}
