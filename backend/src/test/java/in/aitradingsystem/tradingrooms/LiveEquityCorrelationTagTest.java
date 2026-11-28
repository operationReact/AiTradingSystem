package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LiveEquityCorrelationTagTest {

    @Test
    void generatedTagsMatchTheBrokerTransportGrammar() {
        for (int i = 0; i < 1000; i++) {
            String tag = LiveEquityCorrelationTag.generate();
            assertThat(tag).matches("[A-Za-z0-9_-]{8,20}");
            assertThat(LiveEquityCorrelationTag.isValid(tag)).isTrue();
        }
    }

    @Test
    void generatedTagsAreEffectivelyUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            assertThat(seen.add(LiveEquityCorrelationTag.generate()))
                .as("correlation tags must not collide")
                .isTrue();
        }
    }

    @Test
    void invalidTagsAreRejected() {
        assertThat(LiveEquityCorrelationTag.isValid(null)).isFalse();
        assertThat(LiveEquityCorrelationTag.isValid("short")).isFalse();           // <8
        assertThat(LiveEquityCorrelationTag.isValid("MT this has spaces")).isFalse();
        assertThat(LiveEquityCorrelationTag.isValid("MT=illegalchar")).isFalse();
        assertThat(LiveEquityCorrelationTag.isValid("A".repeat(21))).isFalse();    // >20
        assertThat(LiveEquityCorrelationTag.isValid("MTabcdef")).isTrue();         // 8 ok
    }
}
