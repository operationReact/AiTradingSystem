package in.aitradingsystem.news.identity;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Point-in-time issuer identity resolution for already-extracted news entity mentions.
 *
 * <p>This class never downloads instruments or guesses ticker symbols from prose. Callers must
 * supply source-attested, dated instrument/issuer identities from the canonical instrument master.
 * A matching name is not evidence that an issuer was available at an earlier decision timestamp.
 */
public final class NewsIssuerIdentityResolver {
    private NewsIssuerIdentityResolver() {}

    public enum Exchange { NSE, BSE, INDEX }

    public enum Status {
        VERIFIED, AMBIGUOUS, UNKNOWN, UNKNOWN_CATALOG, NOT_AVAILABLE_AS_OF
    }

    /**
     * An issuer or index identity in a dated instrument-source snapshot.
     * issuerKey must identify the actual issuer lineage (or index), never a mutable ticker.
     * For cross-listed securities, use the same issuerKey with different exchange entries.
     */
    public record Identity(
        Exchange exchange,
        String issuerKey,
        String isin,
        String tradingSymbol,
        String legalName,
        Set<String> sourceAttestedAliases,
        LocalDate effectiveFrom,
        LocalDate effectiveUntilExclusive,
        Instant sourceKnownAt
    ) {
        public Identity {
            Objects.requireNonNull(exchange, "exchange");
            Objects.requireNonNull(issuerKey, "issuerKey");
            Objects.requireNonNull(sourceKnownAt, "sourceKnownAt");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            if (issuerKey.isBlank() || issuerKey.length() > 120) {
                throw new IllegalArgumentException("invalid canonical issuer key");
            }
            if (effectiveUntilExclusive != null && !effectiveUntilExclusive.isAfter(effectiveFrom)) {
                throw new IllegalArgumentException("invalid effective identity window");
            }
            if (isin != null && !isin.isBlank() && !isin.matches("[A-Za-z0-9]{12}")) {
                throw new IllegalArgumentException("invalid ISIN shape");
            }
            sourceAttestedAliases = sourceAttestedAliases == null ? Set.of() : Set.copyOf(sourceAttestedAliases);
        }

        public boolean effectiveOn(LocalDate date) {
            return !date.isBefore(effectiveFrom)
                && (effectiveUntilExclusive == null || date.isBefore(effectiveUntilExclusive));
        }

        public boolean availableAt(Instant instant) {
            return !sourceKnownAt.isAfter(instant);
        }

        /** Exact source-attested name, symbol, or ISIN. Never substring matching. */
        public boolean matches(String normalizedMention) {
            if (normalizedMention.isBlank()) return false;
            if (normalizedMention.equals(normalize(isin))
                || normalizedMention.equals(normalize(tradingSymbol))
                || normalizedMention.equals(normalize(legalName))) {
                return true;
            }
            return sourceAttestedAliases.stream().anyMatch(a -> normalizedMention.equals(normalize(a)));
        }
    }

    public record Resolution(Status status, List<String> issuerKeys, List<Identity> evidence) {
        public Resolution {
            issuerKeys = List.copyOf(issuerKeys);
            evidence = List.copyOf(evidence);
        }
        public boolean verified() { return status == Status.VERIFIED; }
    }

    /**
     * @param mention exact extracted entity mention, not an arbitrary news body
     * @param exchange optional exchange constraint; INDEX is distinct from cash equity
     * @param eventDate relevant market/listing date, sourced from the event
     * @param decisionAt as-of observation time; source identities published later cannot be used
     */
    public static Resolution resolve(
        String mention, Exchange exchange, LocalDate eventDate, Instant decisionAt,
        Collection<Identity> identities) {

        Objects.requireNonNull(eventDate, "eventDate");
        Objects.requireNonNull(decisionAt, "decisionAt");
        if (identities == null || identities.isEmpty()) {
            return new Resolution(Status.UNKNOWN_CATALOG, List.of(), List.of());
        }
        String q = normalize(mention);
        if (q.isBlank()) return new Resolution(Status.UNKNOWN, List.of(), List.of());

        List<Identity> matches = new ArrayList<>();
        for (Identity identity : identities) {
            if (identity == null) continue;
            if (exchange != null && identity.exchange() != exchange) continue;
            if (identity.matches(q)) matches.add(identity);
        }
        if (matches.isEmpty()) return new Resolution(Status.UNKNOWN, List.of(), List.of());

        List<Identity> valid = matches.stream()
            .filter(i -> i.availableAt(decisionAt) && i.effectiveOn(eventDate))
            .sorted(Comparator.comparing(Identity::issuerKey)
                .thenComparing(i -> i.exchange().name())
                .thenComparing(Identity::effectiveFrom))
            .toList();
        if (valid.isEmpty()) {
            // Do not expose future issuer identity evidence to an historical decision.
            return new Resolution(Status.NOT_AVAILABLE_AS_OF, List.of(), List.of());
        }

        TreeSet<String> keys = new TreeSet<>();
        for (Identity identity : valid) keys.add(identity.issuerKey());
        return new Resolution(keys.size() == 1 ? Status.VERIFIED : Status.AMBIGUOUS,
            List.copyOf(keys), valid);
    }

    /** Conservative normalization: Unicode NFKC, case-folding, whitespace/punctuation only. */
    static String normalize(String input) {
        if (input == null) return "";
        return Normalizer.normalize(input, Normalizer.Form.NFKC)
            .toUpperCase(Locale.ROOT)
            .replaceAll("[\\p{Punct}\\p{IsPunctuation}]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
