package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationCodeReviewContractTest {
    @Test
    void reviewKeepsPersistenceAuthUiAndCiCorrectionsOpen() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-code-review.md"));
        assertThat(text).contains("not production-integrated yet");
        assertThat(text).contains("replace/delegate in-memory account preference, idempotency, fill/history state");
        assertThat(text).contains("derive account identity from authenticated principal");
        assertThat(text).contains("implement real Flutter surfaces rather than contract-only tests");
        assertThat(text).contains("No merge recommendation at this checkpoint");
    }
}
