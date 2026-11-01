package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrBodyContractTest {
    @Test
    void prBodyDescribesFoundationBlockersAndSafetyAccurately() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-body.md"));
        assertThat(text).contains("Why this remains Draft");
        assertThat(text).contains("temporary in-process adapters");
        assertThat(text).contains("Actual Flutter Home/AI/Portfolio/More controls and lifecycle UI are also still pending");
        assertThat(text).contains("PAPER only. LIVE unavailable. Broker submission unavailable. Angel One read-only");
    }
}
