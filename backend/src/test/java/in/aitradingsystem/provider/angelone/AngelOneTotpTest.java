package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AngelOneTotpTest {

    // RFC 6238 Appendix B, SHA-1 key "12345678901234567890", 6-digit truncation of the 8-digit reference values.
    private static final String RFC_SECRET_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesRfc6238ReferenceVectors() {
        assertThat(AngelOneTotp.generate(RFC_SECRET_BASE32, Instant.ofEpochSecond(59))).isEqualTo("287082");
        assertThat(AngelOneTotp.generate(RFC_SECRET_BASE32, Instant.ofEpochSecond(1111111109L))).isEqualTo("081804");
        assertThat(AngelOneTotp.generate(RFC_SECRET_BASE32, Instant.ofEpochSecond(1234567890L))).isEqualTo("005924");
    }

    @Test
    void ignoresSpacesHyphensAndPadding() {
        assertThat(AngelOneTotp.generate("gezd gnbv-gy3t qojq gezd gnbv gy3t qojq=", Instant.ofEpochSecond(59)))
                .isEqualTo("287082");
    }

    @Test
    void rejectsNonBase32Secret() {
        assertThatThrownBy(() -> AngelOneTotp.generate("not*base32!", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
