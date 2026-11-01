package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationProductPrinciplesContractTest {
    @Test
    void principlesKeepAutomationSafeAndCustomerFocused() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-product-principles.md"));
        assertThat(text).contains("automation never bypasses risk");
        assertThat(text).contains("One ledger");
        assertThat(text).contains("Explicit opt-in");
        assertThat(text).contains("Fail closed");
        assertThat(text).contains("PAPER simulation is independent of Angel One order submission");
        assertThat(text).contains("CI green, mockups and contracts do not equal physical-device/product acceptance");
    }
}
