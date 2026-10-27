package in.aitradingsystem.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.security.api-key=test-operator-key-0123456789")
public abstract class AbstractPostgresIT {
    public static final String API_KEY = "test-operator-key-0123456789";

    /** Singleton container: started once per JVM so the cached Spring context survives across test classes. */
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
