package in.aitradingsystem.provider.upstox;

import java.time.LocalDate;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Read-only identity guard for matching a provider equity key to a dated
 * canonical listing episode. Provider tokens alone never establish identity.
 */
public final class UpstoxListingIdentity {
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{10}");
    private UpstoxListingIdentity() {}

    public record Episode(long listingId, String exchange, String isin,
                          LocalDate effectiveFrom, LocalDate effectiveThrough) {
        public Episode {
            if (listingId <= 0 || !("NSE".equals(exchange) || "BSE".equals(exchange))
                    || isin == null || !ISIN.matcher(isin).matches()
                    || effectiveFrom == null
                    || (effectiveThrough != null && effectiveThrough.isBefore(effectiveFrom))) {
                throw new IllegalArgumentException("Invalid canonical listing episode");
            }
        }
    }

    /**
     * Fail closed unless exchange, ISIN and the observed trading date agree.
     * A caller must obtain the episode from a trusted, versioned listing master;
     * this function does not validate the listing master's provenance.
     */
    public static long requireMatchingEpisode(String providerKey, LocalDate tradingDate,
                                              Episode episode) {
        Objects.requireNonNull(episode, "listing episode required");
        if (tradingDate == null || providerKey == null
                || !providerKey.equals(("NSE".equals(episode.exchange()) ? "NSE_EQ|" : "BSE_EQ|")
                        + episode.isin())
                || tradingDate.isBefore(episode.effectiveFrom())
                || (episode.effectiveThrough() != null
                    && tradingDate.isAfter(episode.effectiveThrough()))) {
            throw new IllegalArgumentException("Unverified provider-to-listing episode");
        }
        return episode.listingId();
    }
}
