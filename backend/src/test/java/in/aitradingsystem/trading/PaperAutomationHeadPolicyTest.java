package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationHeadPolicyTest {
    @Test
    void movingHeadInvalidatesEarlierAcceptance() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-head-policy.md"));
        assertThat(text).contains("latest exact head");
        assertThat(text).contains("historical evidence only");
        assertThat(text).contains("affected gates must be evaluated again");
    }
}
