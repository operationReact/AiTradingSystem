package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class UpstoxListingIdentityTest {
    private static final String KEY = "NSE_EQ|INE002A01018";

    private static UpstoxListingIdentity.Episode episode() {
        return new UpstoxListingIdentity.Episode(42, "NSE", "INE002A01018",
                LocalDate.of(2020, 1, 1), LocalDate.of(2025, 12, 31));
    }

    @Test
    void matchesOnlyWithinCanonicalListingEpisode() {
        assertThat(UpstoxListingIdentity.requireMatchingEpisode(KEY,
                LocalDate.of(2023, 5, 3), episode())).isEqualTo(42);
        assertThatThrownBy(() -> UpstoxListingIdentity.requireMatchingEpisode(KEY,
                LocalDate.of(2026, 1, 1), episode()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UpstoxListingIdentity.requireMatchingEpisode(KEY,
                LocalDate.of(2019, 12, 31), episode()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsProviderMismatchAndMalformedEpisodes() {
        assertThatThrownBy(() -> UpstoxListingIdentity.requireMatchingEpisode(
                "BSE_EQ|INE002A01018", LocalDate.of(2023, 5, 3), episode()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UpstoxListingIdentity.requireMatchingEpisode(
                "NSE_EQ|INE002A01019", LocalDate.of(2023, 5, 3), episode()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UpstoxListingIdentity.Episode(42, "NSE",
                "INE002A01018", LocalDate.of(2025, 1, 1), LocalDate.of(2024, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UpstoxListingIdentity.requireMatchingEpisode(
                KEY, null, episode())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportsOpenEndedEpisodeButNeverInfersItsIdentity() {
        var open = new UpstoxListingIdentity.Episode(43, "BSE", "INE002A01018",
                LocalDate.of(2024, 1, 1), null);
        assertThat(UpstoxListingIdentity.requireMatchingEpisode("BSE_EQ|INE002A01018",
                LocalDate.of(2026, 11, 12), open)).isEqualTo(43);
    }
}
