package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationFinalPrePrTest {
    @Test
    void prCreationDoesNotAuthorizeMergeDeploymentOrLive() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-final-pre-pr.md"));
        assertThat(text).contains("create Draft PR");
        assertThat(text).contains("No merge, deployment or LIVE action follows from PR creation");
    }
}
