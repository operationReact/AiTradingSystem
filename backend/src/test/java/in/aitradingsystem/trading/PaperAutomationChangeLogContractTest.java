package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationChangeLogContractTest {
    @Test
    void changeLogRecordsFoundationWithoutUnsafeMutations() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-change-log.md"));
        assertThat(text).contains("PAPER/LIVE + Manual/Automated");
        assertThat(text).contains("full automated journey and safety tests");
        assertThat(text).contains("No production deployment, LIVE broker submission, DNS/secrets/signing/store changes were performed");
    }
}
