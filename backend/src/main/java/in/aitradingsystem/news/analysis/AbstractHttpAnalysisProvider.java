package in.aitradingsystem.news.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.NewsProperties;

/** Shared HTTP plumbing: bounded timeouts, and mapping of transport and status failures to safe codes. */
abstract class AbstractHttpAnalysisProvider implements NewsAnalysisProvider {
    protected final NewsProperties props;
    protected final RestClient client;
    protected final ObjectMapper mapper;

    protected AbstractHttpAnalysisProvider(String baseUrl, NewsProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeoutMs());
        factory.setReadTimeout(props.readTimeoutMs());
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    protected JsonNode post(String path, Map<String, String> headers, Object body) {
        try {
            var spec = client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body);
            headers.forEach(spec::header);
            String response = spec.retrieve().body(String.class);
            return mapper.readTree(response);
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 429) throw new AiProviderException(AiErrorCode.RATE_LIMITED, name() + " rate limit reached");
            throw new AiProviderException(AiErrorCode.HTTP_ERROR, name() + " returned HTTP " + status.value());
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                throw new AiProviderException(AiErrorCode.TIMEOUT, name() + " request timed out");
            }
            throw new AiProviderException(AiErrorCode.HTTP_ERROR, name() + " is unreachable");
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AiProviderException(AiErrorCode.INVALID_RESPONSE, name() + " returned an unreadable body");
        }
    }
}
