package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationRiskAuthorityContractTest {
    @Test
    void newMinimumChecksCannotWeakenPriorAuthoritativeRiskPolicy() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-risk-authority.md"));
        assertThat(text).contains("must not replace stricter authoritative risk controls");
        assertThat(text).contains("existing risk limits/exposure/approval rules execute");
        assertThat(text).contains("only an accepted decision may proceed to persisted PAPER execution");
        assertThat(text).contains("must reject even if the new minimum checks would pass");
        assertThat(text).contains("must not weaken it");
    }
}
