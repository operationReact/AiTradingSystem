package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationDraftMarkerTest {
    @Test
    void branchRemainsDraftUntilDefinitionOfDoneIsEvidenced() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-draft-marker.md"));
        assertThat(text).contains("DRAFT");
        assertThat(text).contains("Do not merge until `docs/product/paper-automation-definition-of-done.md` is evidenced");
    }
}
