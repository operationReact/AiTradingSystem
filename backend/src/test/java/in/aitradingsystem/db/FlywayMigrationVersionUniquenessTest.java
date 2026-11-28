package in.aitradingsystem.db;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class FlywayMigrationVersionUniquenessTest {
    @Test
    void migrationVersionsAreUnique() throws Exception {
        URL migrationDirectory = Thread.currentThread()
            .getContextClassLoader()
            .getResource("db/migration");
        assertNotNull(migrationDirectory, "Flyway migration resources must be on the test classpath");
        Path migrationRoot = Path.of(migrationDirectory.toURI());
        for (String requiredMigration : List.of(
                "V133__expanded_english_news_source_policy.sql",
                "V134__news_analysis_event_and_result_metrics.sql",
                "V135__news_english_display_translation.sql",
                "V139__customer_trading_room_thesis.sql",
                "V140__customer_live_intraday_equity_order_custody.sql",
                "V141__live_equity_operator_controls.sql",
                "V142__live_intraday_strategy_proposal.sql")) {
            assertTrue(Files.isRegularFile(migrationRoot.resolve(requiredMigration)),
                () -> "Production Flyway history requires " + requiredMigration);
        }

        Map<Integer, List<String>> migrationsByVersion = new TreeMap<>();
        try (Stream<Path> files = Files.list(migrationRoot)) {
            files.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
                .forEach(name -> {
                    int separator = name.indexOf("__");
                    assertTrue(separator > 1, () -> "Invalid Flyway migration filename: " + name);
                    String versionText = name.substring(1, separator);
                    assertFalse(versionText.isEmpty() || !versionText.chars().allMatch(Character::isDigit),
                        () -> "Invalid Flyway migration version: " + name);
                    migrationsByVersion
                        .computeIfAbsent(Integer.parseInt(versionText), ignored -> new ArrayList<>())
                        .add(name);
                });
        }

        String duplicates = migrationsByVersion.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(entry -> entry.getKey() + ": " + String.join(", ", entry.getValue()))
            .collect(Collectors.joining("; "));
        assertTrue(duplicates.isEmpty(), () -> "Duplicate Flyway migration versions: " + duplicates);
    }
}
