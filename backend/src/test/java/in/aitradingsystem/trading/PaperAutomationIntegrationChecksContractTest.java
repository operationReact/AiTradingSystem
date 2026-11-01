package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationIntegrationChecksContractTest {
    @Test
    void integrationMustInspectExistingAuthorityBeforeSchemaOrLedgerChanges() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-integration-checks.md"));
        assertThat(text).contains("existing authoritative service");
        assertThat(text).contains("persisted table/model");
        assertThat(text).contains("account-scoping mechanism");
        assertThat(text).contains("transaction boundary");
        assertThat(text).contains("Do not create a new Flyway migration merely to mirror state already represented");
    }
}
