package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationSecurityBoundaryContractTest {
    @Test
    void integrationRequiresAuthenticatedAccountAndServerAuthority() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-security-boundary.md"));
        assertThat(text).contains("authenticated server session/principal");
        assertThat(text).contains("Never accept an arbitrary account ID from the mobile client as authority");
        assertThat(text).contains("Emergency stop is server-authoritative");
        assertThat(text).contains("LIVE and broker submission remain unavailable");
    }
}
