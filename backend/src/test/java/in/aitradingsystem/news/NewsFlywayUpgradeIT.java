package in.aitradingsystem.news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class NewsFlywayUpgradeIT {
    @Test
    void upgradesExistingV1ToV11WithoutDroppingLegacyNewsTables() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            Flyway baseline = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("11")
                .load();
            baseline.migrate();

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS phase6_upgrade_sentinel (id INTEGER PRIMARY KEY)");
                statement.execute("INSERT INTO phase6_upgrade_sentinel (id) VALUES (1)");
            }

            Flyway upgraded = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();
            upgraded.migrate();
            upgraded.validate();

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("SELECT COUNT(*) FROM phase6_upgrade_sentinel")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
                try (var result = statement.executeQuery(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '12' AND success = true")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
                try (var result = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('news_item', 'news_summary')")) {
                    assertTrue(result.next());
                    assertEquals(2, result.getInt(1));
                }
            }
        }
    }
}
