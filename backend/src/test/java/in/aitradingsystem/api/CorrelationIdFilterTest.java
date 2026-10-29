package in.aitradingsystem.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void cleanup() { MDC.clear(); }

    @Test
    void generatesWhenAbsentAndClearsMdc() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, assertingChain(response, null));
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).matches("[0-9a-f-]{36}");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void preservesValidCallerId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "caller_123.ok-id");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, assertingChain(response, "caller_123.ok-id"));
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("caller_123.ok-id");
    }

    @Test
    void replacesOversizedId() throws Exception {
        assertRejected("a".repeat(65));
    }

    @Test
    void replacesInvalidId() throws Exception {
        assertRejected("bad id/with spaces");
    }

    private void assertRejected(String supplied) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, supplied);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, assertingChain(response, null));
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotEqualTo(supplied).matches("[0-9a-f-]{36}");
    }

    private FilterChain assertingChain(MockHttpServletResponse response, String expected) {
        return (request, ignored) -> {
            String current = MDC.get(CorrelationIdFilter.MDC_KEY);
            assertThat(current).isNotBlank();
            if (expected != null) assertThat(current).isEqualTo(expected);
            assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(current);
        };
    }
}
