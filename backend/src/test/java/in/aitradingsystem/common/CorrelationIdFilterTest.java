package in.aitradingsystem.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void preservesAnySafeCallerIdUpTo64Characters() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "a.b_c-1");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, assertingChain(response, "a.b_c-1"));
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("a.b_c-1");
    }

    @Test
    void replacesOversizedId() throws Exception { assertRejected("a".repeat(65)); }

    @Test
    void replacesInvalidId() throws Exception { assertRejected("bad id/with spaces"); }

    @Test
    void doesNotLeakCorrelationIdBetweenSequentialRequests() throws Exception {
        var first = new MockHttpServletRequest();
        first.addHeader(CorrelationIdFilter.HEADER, "first-request");
        var firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, assertingChain(firstResponse, "first-request"));
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();

        var second = new MockHttpServletRequest();
        var secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, assertingChain(secondResponse, null));
        assertThat(secondResponse.getHeader(CorrelationIdFilter.HEADER))
            .isNotEqualTo("first-request")
            .matches("[0-9a-f-]{36}");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void clearsMdcEvenWhenDownstreamThrows() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNotBlank();
            throw new jakarta.servlet.ServletException("downstream failure");
        })).isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    private void assertRejected(String supplied) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, supplied);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, assertingChain(response, null));
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotEqualTo(supplied).matches("[0-9a-f-]{36}");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
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
