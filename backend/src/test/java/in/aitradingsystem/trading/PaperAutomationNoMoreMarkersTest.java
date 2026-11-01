package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationNoMoreMarkersTest {
    @Test
    void noMoreMarkers() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-no-more-markers.md"))).contains("NO MORE MARKERS");
    }
}
