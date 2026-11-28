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

        Map<Integer, List<String>> migrationsByVersion = new TreeMap<>();
        try (Stream<Path> files = Files.list(Path.of(migrationDirectory.toURI()))) {
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
