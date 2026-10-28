package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class NewsApiTokenFilterTest {
    private final NewsApiTokenFilter filter = new NewsApiTokenFilter("test-news-token", "https://app.example.com");

    @Test
    void rejectsMissingAndIncorrectBearerTokens() throws Exception {
        for (String header : new String[] {"", "Bearer wrong-token", "Basic test-news-token"}) {
            var request = new MockHttpServletRequest("GET", "/api/news");
            if (!header.isEmpty()) request.addHeader("Authorization", header);
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertEquals(401, response.getStatus());
            assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
            assertFalse(chain.getRequest() != null);
        }
    }

    @Test
    void permitsCorrectBearerTokenForReadOnlyNewsEndpoint() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/news");
        request.addHeader("Authorization", "Bearer test-news-token");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);
    }

    @Test
    void doesNotInterceptOtherApiEndpoints() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/news/articles");
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertTrue(chain.getRequest() != null);
    }

    @Test
    void allowsPreflightOnlyFromConfiguredOrigin() throws Exception {
        var allowed = new MockHttpServletRequest("OPTIONS", "/api/news");
        allowed.addHeader("Origin", "https://app.example.com");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(allowed, response, chain);
        assertEquals(204, response.getStatus());
        assertEquals("https://app.example.com", response.getHeader("Access-Control-Allow-Origin"));
        assertTrue(chain.getRequest() == null);

        var rejected = new MockHttpServletRequest("OPTIONS", "/api/news");
        rejected.addHeader("Origin", "https://attacker.example");
        var rejectedResponse = new MockHttpServletResponse();
        filter.doFilter(rejected, rejectedResponse, new MockFilterChain());
        assertEquals(401, rejectedResponse.getStatus());
        assertTrue(rejectedResponse.getHeader("Access-Control-Allow-Origin") == null);
    }
}
