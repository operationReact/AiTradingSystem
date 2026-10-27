package in.aitradingsystem.instrument;

import in.aitradingsystem.config.AngelOneProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

/** Downloads only from the configured, allow-listed Angel One instrument-master HTTPS URL. */
@Component
public class InstrumentMasterDownloader {
    private final AngelOneProperties properties;
    private final HttpClient client;

    public InstrumentMasterDownloader(AngelOneProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public byte[] download() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(properties.instrumentMasterUrl()))
                .timeout(Duration.ofMillis(properties.readTimeoutMs()))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            // The BodySubscriber completes only after the complete body is received, so the request timeout covers
            // the download itself. It also cancels as soon as the byte limit is exceeded.
            HttpResponse<byte[]> response = client.send(request,
                    info -> boundedBody(properties.instrumentMasterMaxBytes()));
            if (response.statusCode() != 200) {
                throw new InstrumentMasterException("SOURCE_HTTP_" + response.statusCode(),
                        "Angel One instrument-master source returned HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new InstrumentMasterException("SOURCE_EMPTY", "Instrument-master source returned an empty body");
            }
            return body;
        } catch (InstrumentMasterException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new InstrumentMasterException("SOURCE_TIMEOUT", "Angel One instrument-master download timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InstrumentMasterException("SOURCE_INTERRUPTED", "Instrument-master download was interrupted");
        } catch (IOException e) {
            InstrumentMasterException known = findInstrumentMasterException(e);
            if (known != null) throw known;
            throw new InstrumentMasterException("SOURCE_UNAVAILABLE",
                    "Angel One instrument-master source could not be read");
        } catch (IllegalArgumentException e) {
            throw new InstrumentMasterException("SOURCE_UNAVAILABLE",
                    "Angel One instrument-master source could not be read");
        } catch (RuntimeException e) {
            InstrumentMasterException known = findInstrumentMasterException(e);
            if (known != null) throw known;
            throw new InstrumentMasterException("SOURCE_UNAVAILABLE",
                    "Angel One instrument-master source could not be read");
        }
    }

    static HttpResponse.BodySubscriber<byte[]> boundedBody(int maxBytes) {
        return new HttpResponse.BodySubscriber<>() {
            private final ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 1_048_576));
            private final CompletableFuture<byte[]> body = new CompletableFuture<>();
            private Flow.Subscription subscription;
            private int total;

            @Override
            public CompletionStage<byte[]> getBody() {
                return body;
            }

            @Override
            public void onSubscribe(Flow.Subscription incoming) {
                if (subscription != null) {
                    incoming.cancel();
                    return;
                }
                subscription = incoming;
                incoming.request(1);
            }

            @Override
            public void onNext(List<ByteBuffer> buffers) {
                if (body.isDone()) return;
                for (ByteBuffer buffer : buffers) {
                    int length = buffer.remaining();
                    if (length > maxBytes - total) {
                        subscription.cancel();
                        body.completeExceptionally(new InstrumentMasterException("SOURCE_TOO_LARGE",
                                "Instrument-master download exceeds configured size limit"));
                        return;
                    }
                    byte[] chunk = new byte[length];
                    buffer.get(chunk);
                    output.write(chunk, 0, length);
                    total += length;
                }
                subscription.request(1);
            }

            @Override
            public void onError(Throwable error) {
                body.completeExceptionally(error);
            }

            @Override
            public void onComplete() {
                body.complete(output.toByteArray());
            }
        };
    }

    private static InstrumentMasterException findInstrumentMasterException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InstrumentMasterException masterException) return masterException;
            current = current.getCause();
        }
        return null;
    }
}
