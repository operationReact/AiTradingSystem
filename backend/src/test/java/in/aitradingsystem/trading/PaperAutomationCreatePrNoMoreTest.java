package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationCreatePrNoMoreTest {
    @Test
    void createPrNoMore() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-create-pr-no-more.md"))).contains("CREATE PR. NO MORE");
    }
}
