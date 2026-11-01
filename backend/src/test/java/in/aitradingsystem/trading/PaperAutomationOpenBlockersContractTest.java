package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationOpenBlockersContractTest {
    @Test
    void blockersRemainExplicitUntilReleaseEvidenceExists() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-open-blockers.md"));
        assertThat(text).contains("Authoritative persistence integration");
        assertThat(text).contains("Real Secure Sign In");
        assertThat(text).contains("Actual Flutter implementation");
        assertThat(text).contains("Angel One read-only integration");
        assertThat(text).contains("Canonical Figma visual evidence");
        assertThat(text).contains("Android real-backend/device acceptance");
        assertThat(text).contains("LIVE broker submission is deliberately not an open blocker");
    }
}
