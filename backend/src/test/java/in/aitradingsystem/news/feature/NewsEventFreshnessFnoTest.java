package in.aitradingsystem.news.feature;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.news.feature.NewsEventClassifier.EventClass;
import in.aitradingsystem.news.feature.NewsFreshness.Freshness;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pure, deterministic unit tests for event classes, blackout, freshness and F&amp;O underlying resolution. */
class NewsEventFreshnessFnoTest {
    private static final Instant PUBLISHED = Instant.parse("2026-11-24T09:00:00Z");

    // ---- event classification ------------------------------------------------------------------

    @Test
    void analyzerCategoriesMapToEventClassesAndUnknownFailsClosed() {
        assertThat(NewsEventClassifier.classify("EARNINGS")).isEqualTo(EventClass.EARNINGS_RESULT);
        assertThat(NewsEventClassifier.classify("REGULATORY")).isEqualTo(EventClass.REGULATORY_ACTION);
        assertThat(NewsEventClassifier.classify("CORPORATE_ACTION")).isEqualTo(EventClass.CORPORATE_ACTION);
        assertThat(NewsEventClassifier.classify("MANAGEMENT")).isEqualTo(EventClass.MANAGEMENT_CHANGE);
        assertThat(NewsEventClassifier.classify("MACRO")).isEqualTo(EventClass.MACRO_BACKDROP);
        assertThat(NewsEventClassifier.classify("SECTOR")).isEqualTo(EventClass.SECTOR_THEME);
        assertThat(NewsEventClassifier.classify("OTHER")).isEqualTo(EventClass.GENERAL);
        assertThat(NewsEventClassifier.classify("made-up")).isEqualTo(EventClass.UNCLASSIFIED);
        assertThat(NewsEventClassifier.classify(null)).isEqualTo(EventClass.UNCLASSIFIED);
    }

    @Test
    void onlyHighMaterialityCompanySpecificEventsOpenABlackout() {
        Instant until = NewsEventClassifier.blackoutUntil(EventClass.EARNINGS_RESULT,
            new BigDecimal("0.70"), PUBLISHED);
        assertThat(until).isEqualTo(PUBLISHED.plusSeconds(86_400));
        // Just below the threshold: no blackout.
        assertThat(NewsEventClassifier.blackoutUntil(EventClass.EARNINGS_RESULT,
            new BigDecimal("0.69"), PUBLISHED)).isNull();
        // Macro and sector news never open a company blackout, however material.
        assertThat(NewsEventClassifier.blackoutUntil(EventClass.MACRO_BACKDROP,
            new BigDecimal("1.00"), PUBLISHED)).isNull();
        assertThat(NewsEventClassifier.blackoutUntil(EventClass.UNCLASSIFIED,
            new BigDecimal("1.00"), PUBLISHED)).isNull();
    }

    @Test
    void blackoutIsActiveOnlyBeforeItsEnd() {
        Instant until = PUBLISHED.plusSeconds(86_400);
        assertThat(NewsEventClassifier.inBlackout(until, PUBLISHED.plusSeconds(3600))).isTrue();
        assertThat(NewsEventClassifier.inBlackout(until, until)).isFalse();
        assertThat(NewsEventClassifier.inBlackout(null, PUBLISHED)).isFalse();
    }

    // ---- freshness -----------------------------------------------------------------------------

    @Test
    void freshnessUsesFirstThirdOfTheValidityWindow() {
        Instant validUntil = PUBLISHED.plusSeconds(3 * 3600); // 3h window
        assertThat(NewsFreshness.of(PUBLISHED, validUntil, PUBLISHED.plusSeconds(3600))).isEqualTo(Freshness.FRESH);
        assertThat(NewsFreshness.of(PUBLISHED, validUntil, PUBLISHED.plusSeconds(3600 + 1))).isEqualTo(Freshness.AGING);
        assertThat(NewsFreshness.of(PUBLISHED, validUntil, PUBLISHED.plusSeconds(2 * 3600))).isEqualTo(Freshness.AGING);
    }

    @Test
    void unknownInputsAreNeverPresentedAsFresh() {
        assertThat(NewsFreshness.of(null, PUBLISHED, PUBLISHED)).isEqualTo(Freshness.UNKNOWN);
        assertThat(NewsFreshness.of(PUBLISHED, PUBLISHED, PUBLISHED)).isEqualTo(Freshness.UNKNOWN); // zero window
        assertThat(NewsFreshness.of(PUBLISHED, PUBLISHED.plusSeconds(3600), PUBLISHED.minusSeconds(1)))
            .isEqualTo(Freshness.UNKNOWN); // event after asOf
    }

    // ---- F&O underlying ------------------------------------------------------------------------

    @Test
    void listedStockUnderlyingResolvesAndIsCaseNormalized() {
        FnoUnderlyingPolicy.Resolution r = FnoUnderlyingPolicy.resolve(" reliance ",
            Set.of("RELIANCE", "TCS"), true, "upstox-fno-universe-fixture");
        assertThat(r.status()).isEqualTo(FnoUnderlyingPolicy.Status.LISTED_FNO);
        assertThat(r.underlying()).isEqualTo("RELIANCE");
        assertThat(r.catalogueSource()).isEqualTo("upstox-fno-universe-fixture");
    }

    @Test
    void stockWithoutFuturesIsNotListedButAnAbsentCatalogueIsUnknownNotNotListed() {
        assertThat(FnoUnderlyingPolicy.resolve("SMALLCO", Set.of("RELIANCE"), true, "cat").status())
            .isEqualTo(FnoUnderlyingPolicy.Status.NOT_LISTED);
        // An unavailable catalogue must never be read as "no derivatives".
        FnoUnderlyingPolicy.Resolution absent = FnoUnderlyingPolicy.resolve("RELIANCE", Set.of(), false, "cat");
        assertThat(absent.status()).isEqualTo(FnoUnderlyingPolicy.Status.UNKNOWN_CATALOG);
        assertThat(absent.underlying()).isNull();
        assertThat(FnoUnderlyingPolicy.resolve("RELIANCE", null, true, "cat").status())
            .isEqualTo(FnoUnderlyingPolicy.Status.UNKNOWN_CATALOG);
    }

    @Test
    void indexUnderlyingsResolveOnlyWhenListedInTheCatalogue() {
        assertThat(FnoUnderlyingPolicy.resolve("NIFTY", Set.of("NIFTY", "BANKNIFTY"), true, "cat").underlying())
            .isEqualTo("NIFTY");
        assertThat(FnoUnderlyingPolicy.resolve("FINNIFTY", Set.of("NIFTY"), true, "cat").status())
            .isEqualTo(FnoUnderlyingPolicy.Status.NOT_LISTED);
    }

    @Test
    void blankEntriesInCatalogueAreIgnored() {
        assertThat(FnoUnderlyingPolicy.resolve("", Set.of("RELIANCE"), true, "cat").status())
            .isEqualTo(FnoUnderlyingPolicy.Status.NOT_LISTED);
        assertThat(FnoUnderlyingPolicy.resolve("INFY", java.util.Collections.singleton(" "), true, "cat").status())
            .isEqualTo(FnoUnderlyingPolicy.Status.NOT_LISTED);
    }
}
