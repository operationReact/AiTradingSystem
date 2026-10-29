package in.aitradingsystem.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");

    @BeforeEach
    void setUp() { MDC.put(CorrelationIdFilter.MDC_KEY, "test-correlation"); }

    @AfterEach
    void cleanup() { MDC.clear(); }

    @Test
    void mapsIllegalArgumentToBadRequestWithoutStackTrace() {
        var response = handler.badRequest(new IllegalArgumentException("bad input"), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().correlationId()).isEqualTo("test-correlation");
        assertThat(response.getBody().path()).isEqualTo("/api/test");
        assertThat(response.getBody().toString()).doesNotContain("Exception", "at ");
    }

    @Test
    void mapsResponseStatus() {
        var response = handler.responseStatus(new ResponseStatusException(HttpStatus.NOT_FOUND, "missing"), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().status()).isEqualTo(404);
    }

    @Test
    void unexpectedExceptionDoesNotLeakSecretOrStackTrace() {
        var response = handler.unexpected(new RuntimeException("password=super-secret\n at internal.Service.run"), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().message()).isEqualTo("Internal server error");
        assertThat(response.getBody().toString()).doesNotContain("super-secret", "internal.Service", "RuntimeException");
    }
}
