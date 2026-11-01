package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationFoundationFreezeTest {
    @Test
    void furtherChangesMustBeCiOrIntegrationDriven() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-no-more-foundation.md"));
        assertThat(text).contains("Foundation checkpoint is frozen for review");
        assertThat(text).contains("exact-head CI failures or integration with existing authoritative persistence/UI");
        assertThat(text).contains("not by adding more standalone foundation abstractions");
    }
}
