package in.aitradingsystem.news.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.news.identity.NewsIssuerIdentityResolver.Exchange;
import in.aitradingsystem.news.identity.NewsIssuerIdentityResolver.Identity;
import in.aitradingsystem.news.identity.NewsIssuerIdentityResolver.Status;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Contract tests with synthetic identities only: not a claim of official instrument coverage. */
class NewsIssuerIdentityResolverTest {
    private static final Instant NOW = Instant.parse("2026-11-24T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-24");

    private static Identity id(Exchange exchange, String issuer, String isin, String symbol, String name,
                               Set<String> aliases, LocalDate from, LocalDate until, Instant observed) {
        return new Identity(exchange, issuer, isin, symbol, name, aliases, from, until, observed);
    }

    private static Identity current(Exchange exchange, String issuer, String isin, String symbol,
                                    String name, String... aliases) {
        return id(exchange, issuer, isin, symbol, name, Set.of(aliases),
            LocalDate.parse("2020-01-01"), null, NOW.minusSeconds(3600));
    }

    @Test
    void emptyMasterIsUnknownCatalogNotUnknownCompany() {
        var result = NewsIssuerIdentityResolver.resolve("ANY", null, TODAY, NOW, List.of());
        assertThat(result.status()).isEqualTo(Status.UNKNOWN_CATALOG);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void exactSymbolAndLegalNameUseOneCanonicalIssuerAcrossTwoExchanges() {
        var nse = current(Exchange.NSE, "issuer-123", "IN0000000001", "ACME", "Acme Industries Ltd");
        var bse = current(Exchange.BSE, "issuer-123", "IN0000000001", "ACMEIND", "Acme Industries Ltd");
        var all = List.of(nse, bse);
        var byName = NewsIssuerIdentityResolver.resolve("ACME INDUSTRIES LTD", null, TODAY, NOW, all);
        assertThat(byName.status()).isEqualTo(Status.VERIFIED);
        assertThat(byName.issuerKeys()).containsExactly("issuer-123");
        assertThat(byName.evidence()).hasSize(2);
        var nseOnly = NewsIssuerIdentityResolver.resolve("ACME", Exchange.NSE, TODAY, NOW, all);
        assertThat(nseOnly.status()).isEqualTo(Status.VERIFIED);
        assertThat(nseOnly.evidence()).containsExactly(nse);
    }

    @Test
    void brandAliasOnlyMatchesWhenAttestedAndAvailableAtDecisionTime() {
        var record = id(Exchange.NSE, "issuer-456", "IN0000000002", "ABCNEW", "Alpha Beta Corp",
            Set.of("ABC OLD"), LocalDate.parse("2025-01-01"), null, NOW.plusSeconds(300));
        var past = NewsIssuerIdentityResolver.resolve("ABC OLD", Exchange.NSE, TODAY, NOW, List.of(record));
        assertThat(past.status()).isEqualTo(Status.NOT_AVAILABLE_AS_OF);
        assertThat(past.issuerKeys()).isEmpty();
        assertThat(past.evidence()).isEmpty();
        var future = NewsIssuerIdentityResolver.resolve("ABC OLD", Exchange.NSE, TODAY, NOW.plusSeconds(300),
            List.of(record));
        assertThat(future.status()).isEqualTo(Status.VERIFIED);
    }

    @Test
    void tickerReuseDoesNotLeakCurrentCompanyIntoOlderEvent() {
        var old = id(Exchange.NSE, "old-issuer", "IN0000000003", "REUSE", "Former Industries",
            Set.of(), LocalDate.parse("2010-01-01"), LocalDate.parse("2021-01-01"), NOW.minusSeconds(99999));
        var newer = id(Exchange.NSE, "new-issuer", "IN0000000004", "REUSE", "New Industries",
            Set.of(), LocalDate.parse("2021-01-01"), null, NOW.minusSeconds(99999));
        var oldEvent = NewsIssuerIdentityResolver.resolve("REUSE", Exchange.NSE,
            LocalDate.parse("2019-06-01"), NOW, List.of(old, newer));
        var newEvent = NewsIssuerIdentityResolver.resolve("REUSE", Exchange.NSE,
            LocalDate.parse("2026-01-01"), NOW, List.of(old, newer));
        assertThat(oldEvent.issuerKeys()).containsExactly("old-issuer");
        assertThat(newEvent.issuerKeys()).containsExactly("new-issuer");
    }

    @Test
    void overlappingIssuerAliasesStayAmbiguousInsteadOfPickingFirst() {
        var a = current(Exchange.NSE, "issuer-a", "IN0000000005", "AAA", "Alpha Finance", "GROUP");
        var b = current(Exchange.NSE, "issuer-b", "IN000000000000", "BBB", "Beta Finance", "GROUP");
        // The second synthetic ISIN must have exactly 12 alphanumerics.
        var result = NewsIssuerIdentityResolver.resolve("GROUP", Exchange.NSE, TODAY, NOW, List.of(a, b));
        assertThat(result.status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(result.issuerKeys()).containsExactly("issuer-a", "issuer-b");
        assertThat(result.verified()).isFalse();
    }

    @Test
    void noSubstringOrFuzzyMatchesAndIndexIsNotAnEquity() {
        var share = current(Exchange.NSE, "issuer-share", "IN0000000006", "GREEN", "Green Energy Ltd");
        var index = current(Exchange.INDEX, "index-50", null, "NIFTY", "Nifty 50 Index");
        assertThat(NewsIssuerIdentityResolver.resolve("GREEN ENERGY REPORTS", null, TODAY, NOW,
            List.of(share, index)).status()).isEqualTo(Status.UNKNOWN);
        assertThat(NewsIssuerIdentityResolver.resolve("NIFTY", Exchange.NSE, TODAY, NOW,
            List.of(share, index)).status()).isEqualTo(Status.UNKNOWN);
        assertThat(NewsIssuerIdentityResolver.resolve("NIFTY", Exchange.INDEX, TODAY, NOW,
            List.of(share, index)).issuerKeys()).containsExactly("index-50");
    }

    @Test
    void invalidEffectiveDateWindowAndBadIsinAreRejected() {
        assertThatThrownBy(() -> id(Exchange.NSE, "issuer-a", "BAD", "ABC", "ABC Ltd", Set.of(),
            TODAY, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> id(Exchange.NSE, "issuer-b", null, "XYZ", "XYZ Ltd", Set.of(),
            TODAY, TODAY, NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
