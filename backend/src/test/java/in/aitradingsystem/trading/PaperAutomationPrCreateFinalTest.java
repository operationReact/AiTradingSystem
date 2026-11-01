package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrCreateFinalTest {
    @Test
    void draftPrCoordinatesAreExplicit() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-create-final.md"));
        assertThat(text).contains("Base `dev`");
        assertThat(text).contains("head `product/paper-automation-risk-controls`");
        assertThat(text).contains("PAPER automation: shared risk controls and lifecycle foundation");
        assertThat(text).contains("Do not merge");
    }
}
