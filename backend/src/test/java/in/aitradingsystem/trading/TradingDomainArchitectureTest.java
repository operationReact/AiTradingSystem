package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TradingDomainArchitectureTest {
    @Test
    void simulatedPaperServicesDoNotReferenceBrokerAdapters() throws Exception {
        var root = Path.of("src", "main", "java", "in", "aitradingsystem", "trading");
        try (var files = Files.list(root)) {
            var source = files.filter(path -> path.toString().endsWith(".java"))
                .map(path -> {
                    try { return Files.readString(path); }
                    catch (Exception exception) { throw new RuntimeException(exception); }
                })
                .reduce("", (left, right) -> left + "\n" + right);
            assertThat(source).doesNotContain("AngelOne");
            assertThat(source).doesNotContain("SmartAPI");
            assertThat(source).doesNotContain("placeOrder(");
        }
    }
}
