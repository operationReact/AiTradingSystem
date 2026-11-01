package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrDescriptionContractTest {
    @Test
    void prDescriptionKeepsDraftAndSafetyBoundariesExplicit() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-description.md"));
        assertThat(text).contains("This is intentionally Draft");
        assertThat(text).contains("not a second production ledger");
        assertThat(text).contains("Actual Flutter Home/AI/Portfolio/More controls/lifecycle UI");
        assertThat(text).contains("LIVE unavailable");
        assertThat(text).contains("Broker submission unavailable");
    }
}
