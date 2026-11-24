package in.aitradingsystem.news.feature;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.provider.upstox.UpstoxFnoInstrumentUniverse;
import in.aitradingsystem.tradingrooms.FnoFuturesContractRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Adapter over a FIXTURE universe. These prove the definition (FUT underlyings are LISTED_FNO, options
 * alone are NOT_LISTED, absent catalogue is UNKNOWN_CATALOG) and the provenance string. They do not
 * prove that the production catalogue is loaded.
 */
class FnoUnderlyingCatalogueAdapterTest {
    private static final Instant FETCHED = Instant.parse("2026-11-24T02:00:00Z");
    private static final String SHA = "a".repeat(64);

    private static UpstoxFnoInstrumentUniverse.Contract contract(String underlying, String type) {
        return new UpstoxFnoInstrumentUniverse.Contract(
            "NSE_FO|" + underlying + "|" + type, "UK-" + underlying, underlying, underlying + " " + type,
            type, LocalDate.of(2026, 12, 31), BigDecimal.ZERO, 1, new BigDecimal("5"), BigDecimal.ONE, false);
    }

    private static FnoFuturesContractRegistry.UniverseSnapshot snapshot(UpstoxFnoInstrumentUniverse.Contract... cs) {
        var catalog = new UpstoxFnoInstrumentUniverse.Catalog(List.of(cs), Map.of(), Map.of(), 0, 0, 0);
        return new FnoFuturesContractRegistry.UniverseSnapshot(FETCHED, SHA, catalog);
    }

    private static FnoUnderlyingCatalogueAdapter adapter(Optional<FnoFuturesContractRegistry.UniverseSnapshot> s,
                                                         String state) {
        return new FnoUnderlyingCatalogueAdapter(() -> s, () -> state);
    }

    @Test
    void absentCatalogueIsUnknownNeverNotListed() {
        FnoUnderlyingPolicy.Resolution r = adapter(Optional.empty(), "DISCOVERY_NOT_VERIFIED").resolve("RELIANCE");
        assertThat(r.status()).isEqualTo(FnoUnderlyingPolicy.Status.UNKNOWN_CATALOG);
        assertThat(r.underlying()).isNull();
        assertThat(r.catalogueSource()).contains("DISCOVERY_NOT_VERIFIED");
    }

    @Test
    void futuresUnderlyingIsListedWithCatalogueProvenance() {
        var a = adapter(Optional.of(snapshot(contract("RELIANCE", "FUT"), contract("RELIANCE", "CE"))), "LOADED");
        FnoUnderlyingPolicy.Resolution r = a.resolve("RELIANCE");
        assertThat(r.status()).isEqualTo(FnoUnderlyingPolicy.Status.LISTED_FNO);
        assertThat(r.underlying()).isEqualTo("RELIANCE");
        assertThat(r.catalogueSource()).contains("sha256=" + SHA).contains("fetchedAt=" + FETCHED);
    }

    @Test
    void optionsOnlyUnderlyingIsNotListedBecauseNoFuturesContractExists() {
        var a = adapter(Optional.of(snapshot(contract("ONLYOPT", "CE"), contract("ONLYOPT", "PE"))), "LOADED");
        assertThat(a.resolve("ONLYOPT").status()).isEqualTo(FnoUnderlyingPolicy.Status.NOT_LISTED);
    }

    @Test
    void indexUnderlyingResolvesFromItsOwnFuturesContract() {
        var a = adapter(Optional.of(snapshot(contract("NIFTY", "FUT"), contract("BANKNIFTY", "FUT"))), "LOADED");
        assertThat(a.resolve("NIFTY").underlying()).isEqualTo("NIFTY");
        assertThat(a.resolve("FINNIFTY").status()).isEqualTo(FnoUnderlyingPolicy.Status.NOT_LISTED);
    }

    @Test
    void symbolNormalizationAndNearMissCollisionsAreHandled() {
        var a = adapter(Optional.of(snapshot(contract("RELIANCE", "FUT"))), "LOADED");
        assertThat(a.resolve(" reliance ").status()).isEqualTo(FnoUnderlyingPolicy.Status.LISTED_FNO);
        assertThat(a.resolve("RELIANCEX").status()).isEqualTo(FnoUnderlyingPolicy.Status.NOT_LISTED);
        assertThat(a.resolve("RELIANC").status()).isEqualTo(FnoUnderlyingPolicy.Status.NOT_LISTED);
    }

    @Test
    void otherUnderlyingsFuturesNeverLeakIntoAResolution() {
        var a = adapter(Optional.of(snapshot(contract("TCS", "FUT"))), "LOADED");
        assertThat(a.resolve("INFY").status()).isEqualTo(FnoUnderlyingPolicy.Status.NOT_LISTED);
    }

    @Test
    void blankBaseSymbolIsNotListedEvenWithACatalogue() {
        var a = adapter(Optional.of(snapshot(contract("TCS", "FUT"))), "LOADED");
        assertThat(a.resolve("  ").status()).isEqualTo(FnoUnderlyingPolicy.Status.NOT_LISTED);
        assertThat(a.resolve(null).status()).isEqualTo(FnoUnderlyingPolicy.Status.NOT_LISTED);
    }
}
