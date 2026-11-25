package in.aitradingsystem.news.feature;

import java.util.Locale;
import java.util.Set;

/**
 * Deterministic F&amp;O underlying resolution for one verified cash-equity base symbol.
 *
 * <p>The caller supplies the set of futures underlyings from an explicit catalogue (for example the
 * Upstox F&amp;O universe, contracts of type FUT). When no catalogue is available the result is
 * UNKNOWN_CATALOG, never NOT_LISTED: an absent catalogue must not be read as "no derivatives". A
 * sentiment tag is never mapped to a CE or PE trade direction here.
 */
public final class FnoUnderlyingPolicy {
    private FnoUnderlyingPolicy() {}

    public enum Status { LISTED_FNO, NOT_LISTED, UNKNOWN_CATALOG }

    public record Resolution(Status status, String underlying, String catalogueSource) {}

    public static Resolution resolve(String baseSymbol, Set<String> futuresUnderlyings,
                                     boolean catalogueAvailable, String catalogueSource) {
        String base = baseSymbol == null ? "" : baseSymbol.trim().toUpperCase(Locale.ROOT);
        if (!catalogueAvailable || futuresUnderlyings == null) {
            return new Resolution(Status.UNKNOWN_CATALOG, null, catalogueSource);
        }
        Set<String> normalized = futuresUnderlyings.stream()
            .filter(s -> s != null && !s.isBlank())
            .map(s -> s.trim().toUpperCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!base.isEmpty() && normalized.contains(base)) {
            return new Resolution(Status.LISTED_FNO, base, catalogueSource);
        }
        return new Resolution(Status.NOT_LISTED, null, catalogueSource);
    }
}
