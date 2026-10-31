package in.aitradingsystem.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.security.api-key=test-operator-key-0123456789")
public abstract class AbstractPostgresIT {
    public static final String API_KEY = "test-operator-key-0123456789";

    private static final String EXTERNAL_JDBC_URL = System.getenv("ATS_TEST_POSTGRES_JDBC_URL");
    private static final String EXTERNAL_USERNAME = System.getenv().getOrDefault("ATS_TEST_POSTGRES_USERNAME", "aitrading");
    private static final String EXTERNAL_PASSWORD = System.getenv().getOrDefault("ATS_TEST_POSTGRES_PASSWORD", "aitrading");

    /**
     * Local development keeps the zero-setup Testcontainers fallback. CI supplies an
     * ephemeral runner-local PostgreSQL instance so integration tests do not depend on
     * Docker Hub pull availability or unauthenticated rate limits.
     */
    static final PostgreSQLContainer<?> POSTGRES = EXTERNAL_JDBC_URL == null || EXTERNAL_JDBC_URL.isBlank()
            ? new PostgreSQLContainer<>("postgres:16-alpine")
            : null;

    static {
        if (POSTGRES != null) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            return;
        }

        registry.add("spring.datasource.url", () -> EXTERNAL_JDBC_URL);
        registry.add("spring.datasource.username", () -> EXTERNAL_USERNAME);
        registry.add("spring.datasource.password", () -> EXTERNAL_PASSWORD);
    }
}
