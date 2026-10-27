package in.aitradingsystem.news;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RssFeedClientTest {
    @Test
    void rejectsProviderErrorsAndOversizedResponses() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<java.io.InputStream> failed = response(503, new byte[0]);
        doReturn(failed).when(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        RssFeedClient client = new RssFeedClient(http);
        assertThrows(IOException.class, () -> client.fetch(NewsSource.byId("moneycontrol"), 1024));

        HttpResponse<java.io.InputStream> oversized = response(200, new byte[1025]);
        doReturn(oversized).when(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertThrows(IOException.class, () -> client.fetch(NewsSource.byId("moneycontrol"), 1024));
    }

    @Test
    void propagatesProviderTimeoutForTheJobToHandle() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doThrow(new HttpTimeoutException("timeout")).when(http)
            .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertThrows(HttpTimeoutException.class,
            () -> new RssFeedClient(http).fetch(NewsSource.byId("moneycontrol"), 1024));
    }

    @Test
    void acceptsBoundedRssPayload() throws Exception {
        byte[] body = "<rss/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpClient http = mock(HttpClient.class);
        doReturn(response(200, body)).when(http)
            .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertArrayEquals(body, new RssFeedClient(http).fetch(NewsSource.byId("moneycontrol"), 1024));
    }

    private static HttpResponse<java.io.InputStream> response(int status, byte[] body) {
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        doReturn(status).when(response).statusCode();
        doReturn(new ByteArrayInputStream(body)).when(response).body();
        doReturn(HttpHeaders.of(Map.of("Content-Type", List.of("application/rss+xml")), (name, value) -> true))
            .when(response).headers();
        return response;
    }
}
