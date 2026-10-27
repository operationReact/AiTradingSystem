package in.aitradingsystem.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class InstrumentMasterDownloaderTest {

    @Test
    void boundedBodySubscriberCollectsBodyUnderLimit() {
        var subscriber = InstrumentMasterDownloader.boundedBody(8);
        var result = subscriber.getBody().toCompletableFuture();

        subscriber.onSubscribe(noOpSubscription());
        subscriber.onNext(List.of(
                ByteBuffer.wrap("JSON".getBytes(StandardCharsets.UTF_8)),
                ByteBuffer.wrap("DATA".getBytes(StandardCharsets.UTF_8))));
        subscriber.onComplete();

        assertThat(result.join()).isEqualTo("JSONDATA".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void boundedBodySubscriberCancelsWhenBodyExceedsLimit() {
        var subscriber = InstrumentMasterDownloader.boundedBody(4);
        var result = subscriber.getBody().toCompletableFuture();
        var cancelled = new boolean[] {false};

        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) {}
            @Override public void cancel() { cancelled[0] = true; }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap("TOO-LARGE".getBytes(StandardCharsets.UTF_8))));

        assertThat(cancelled[0]).isTrue();
        assertThatThrownBy(result::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(InstrumentMasterException.class);
        assertThat(result.isCompletedExceptionally()).isTrue();
    }
    
    private static Flow.Subscription noOpSubscription() {
        return new Flow.Subscription() {
            @Override public void request(long n) {}
            @Override public void cancel() {}
        };
    }
}
