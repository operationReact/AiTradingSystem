package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationBrokerBoundaryContractTest {
    @Test
    void angelOneRemainsReadOnlyAndSeparateFromPaperSimulation() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-broker-boundary.md"));
        assertThat(text).contains("read funds");
        assertThat(text).contains("read holdings");
        assertThat(text).contains("read positions");
        assertThat(text).contains("read order book");
        assertThat(text).contains("submit/modify/cancel a LIVE broker order");
        assertThat(text).contains("PAPER automation must remain fully testable without an Angel One account connected");
    }
}
