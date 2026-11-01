package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationDeliveryBoundaryContractTest {
    @Test
    void deliveryBoundaryExcludesProductionAndLiveMutations() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-delivery-boundary.md"));
        assertThat(text).contains("production or cloud deployment");
        assertThat(text).contains("AWS provisioning or mutation");
        assertThat(text).contains("secret creation/rotation");
        assertThat(text).contains("LIVE trading activation");
        assertThat(text).contains("broker order submission");
    }
}
