package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationDataAuthorityContractTest {
    @Test
    void productionIntegrationMustUsePersistedBackendAuthority() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-data-authority.md"));
        assertThat(text).contains("existing persisted backend services");
        assertThat(text).contains("temporary in-process foundation adapters");
        assertThat(text).contains("PAPER orders/fills");
        assertThat(text).contains("unrealized/realized P&L");
        assertThat(text).contains("Flutter renders and requests changes; it does not become the ledger or risk authority");
    }
}
