package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationIdempotencyContractTest {
    @Test
    void productionIdempotencyMustBeAccountScopedPersistedAndConcurrencySafe() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-idempotency.md"));
        assertThat(text).contains("authenticated account plus source opportunity/manual request identity");
        assertThat(text).contains("cannot create another order/fill");
        assertThat(text).contains("cannot close a position twice");
        assertThat(text).contains("database/transaction constraints or equivalent authoritative serialization");
        assertThat(text).contains("in-process set/map behavior demonstrates semantics only");
    }
}
