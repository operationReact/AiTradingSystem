package in.aitradingsystem.news.feature;

import static in.aitradingsystem.news.feature.NewsFeaturePolicy.Status.*;
import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.news.feature.NewsFeaturePolicy.Aggregate;
import in.aitradingsystem.news.feature.NewsFeaturePolicy.Candidate;
import in.aitradingsystem.news.feature.NewsFeaturePolicy.Evaluated;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewsFeaturePolicyTest {
    private static final Instant ASOF = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant PUBLISHED = Instant.parse("2026-11-20T09:00:00Z");

    private static Candidate good() {
        return new Candidate(PUBLISHED, PUBLISHED.plusSeconds(120), "ANALYZED", "INTRADAY",
            new BigDecimal("0.600"), new BigDecimal("0.800"), new BigDecimal("0.700"),
            false, false, true);
    }

    private static Candidate with(java.util.function.UnaryOperator<Candidate> change) {
        return change.apply(good());
    }

    @Test
    void includesVerifiedAnalyzedItemAvailableAtAsOf() {
        assertThat(NewsFeaturePolicy.classify(good(), ASOF)).isEqualTo(INCLUDED);
    }

    @Test
    void excludesArticlePublishedAfterAsOf() {
        Candidate c = with(x -> new Candidate(ASOF.plusSeconds(1), x.analysisAvailableAt(), x.analysisStatus(),
            x.horizon(), x.directionScore(), x.confidence(), x.materiality(), false, false, true));
        assertThat(NewsFeaturePolicy.classify(c, ASOF)).isEqualTo(EXCLUDED_PUBLISHED_AFTER_AS_OF);
    }

    @Test
    void excludesAnalysisCreatedAfterAsOfEvenWhenArticleWasAlreadyPublished() {
        // Lookahead guard: a re-analysis created after the signal must never be used for that signal.
        Candidate c = with(x -> new Candidate(x.publishedAt(), ASOF.plusSeconds(1), x.analysisStatus(),
            x.horizon(), x.directionScore(), x.confidence(), x.materiality(), false, false, true));
        assertThat(NewsFeaturePolicy.classify(c, ASOF)).isEqualTo(EXCLUDED_ANALYSIS_NOT_YET_AVAILABLE);
    }

    @Test
    void analysisCreatedExactlyAtAsOfIsUsable() {
        Candidate c = with(x -> new Candidate(x.publishedAt(), ASOF, x.analysisStatus(),
            x.horizon(), x.directionScore(), x.confidence(), x.materiality(), false, false, true));
        assertThat(NewsFeaturePolicy.classify(c, ASOF)).isEqualTo(INCLUDED);
    }

    @Test
    void excludesUnverifiedInstrumentMapping() {
        Candidate c = with(x -> new Candidate(x.publishedAt(), x.analysisAvailableAt(), x.analysisStatus(),
            x.horizon(), x.directionScore(), x.confidence(), x.materiality(), false, false, false));
        assertThat(NewsFeaturePolicy.classify(c, ASOF)).isEqualTo(EXCLUDED_UNVERIFIED_INSTRUMENT);
    }

    @Test
    void failedAnalysisNeverContributes() {
        Candidate c = with(x -> new Candidate(x.publishedAt(), x.analysisAvailableAt(), "FAILED",
            x.horizon(), null, null, null, false, false, true));
        assertThat(NewsFeaturePolicy.classify(c, ASOF)).isEqualTo(EXCLUDED_ANALYSIS_FAILED);
    }

    @Test
    void staleItemExpiresByHorizon() {
        Candidate intraday = with(x -> new Candidate(PUBLISHED.minusSeconds(0), x.analysisAvailableAt(),
            x.analysisStatus(), "INTRADAY", x.directionScore(), x.confidence(), x.materiality(),
            false, false, true));
        assertThat(NewsFeaturePolicy.classify(intraday, PUBLISHED.plusSeconds(86_400))).isEqualTo(EXCLUDED_EXPIRED);
        Candidate longer = with(x -> new Candidate(PUBLISHED, x.analysisAvailableAt(),
            x.analysisStatus(), "LONGER_TERM", x.directionScore(), x.confidence(), x.materiality(),
            false, false, true));
        assertThat(NewsFeaturePolicy.classify(longer, PUBLISHED.plusSeconds(86_400 * 10L))).isEqualTo(INCLUDED);
    }

    @Test
    void unknownHorizonUsesShortestWindow() {
        assertThat(NewsFeaturePolicy.validUntil(PUBLISHED, "UNKNOWN")).isEqualTo(PUBLISHED.plusSeconds(86_400));
        assertThat(NewsFeaturePolicy.validUntil(PUBLISHED, null)).isEqualTo(PUBLISHED.plusSeconds(86_400));
    }

    @Test
    void lowConfidenceOrMissingScoresAreExcluded() {
        Candidate low = with(x -> new Candidate(x.publishedAt(), x.analysisAvailableAt(), x.analysisStatus(),
            x.horizon(), x.directionScore(), new BigDecimal("0.49"), x.materiality(), false, false, true));
        assertThat(NewsFeaturePolicy.classify(low, ASOF)).isEqualTo(EXCLUDED_LOW_CONFIDENCE);
        Candidate missing = with(x -> new Candidate(x.publishedAt(), x.analysisAvailableAt(), x.analysisStatus(),
            x.horizon(), null, x.confidence(), x.materiality(), false, false, true));
        assertThat(NewsFeaturePolicy.classify(missing, ASOF)).isEqualTo(EXCLUDED_LOW_CONFIDENCE);
    }

    @Test
    void contradictionOrAmbiguityIsUncertainAndExcluded() {
        Candidate contradictory = with(x -> new Candidate(x.publishedAt(), x.analysisAvailableAt(),
            x.analysisStatus(), x.horizon(), x.directionScore(), x.confidence(), x.materiality(),
            true, false, true));
        assertThat(NewsFeaturePolicy.classify(contradictory, ASOF)).isEqualTo(EXCLUDED_UNCERTAIN);
        Candidate ambiguous = with(x -> new Candidate(x.publishedAt(), x.analysisAvailableAt(),
            x.analysisStatus(), x.horizon(), x.directionScore(), x.confidence(), x.materiality(),
            false, true, true));
        assertThat(NewsFeaturePolicy.classify(ambiguous, ASOF)).isEqualTo(EXCLUDED_UNCERTAIN);
    }

    @Test
    void earlierChecksWinOverLaterOnes() {
        // Unverified AND expired: the mapping exclusion is reported first, deterministically.
        Candidate c = new Candidate(PUBLISHED, PUBLISHED.plusSeconds(60), "ANALYZED", "INTRADAY",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false, false, false);
        assertThat(NewsFeaturePolicy.classify(c, PUBLISHED.plusSeconds(86_400L * 30))).isEqualTo(EXCLUDED_UNVERIFIED_INSTRUMENT);
    }

    @Test
    void noIncludedEvidenceYieldsNullDirectionAndUnknownNeverBullish() {
        Aggregate a = NewsFeaturePolicy.aggregate(List.of());
        assertThat(a.netDirection()).isNull();
        assertThat(a.direction()).isEqualTo("UNKNOWN");
        assertThat(a.uncertainty()).isEqualTo("UNKNOWN");
    }

    @Test
    void weightedDirectionUsesOnlyIncludedItems() {
        Candidate bullish = good();
        Candidate bearishLowConfidence = new Candidate(PUBLISHED, PUBLISHED, "ANALYZED", "INTRADAY",
            new BigDecimal("-1.000"), new BigDecimal("0.10"), new BigDecimal("1.000"), false, false, true);
        List<Evaluated> evaluated = List.of(
            new Evaluated(bullish, NewsFeaturePolicy.classify(bullish, ASOF)),
            new Evaluated(bearishLowConfidence, NewsFeaturePolicy.classify(bearishLowConfidence, ASOF)));
        NewsFeaturePolicy.Aggregate a = NewsFeaturePolicy.aggregate(evaluated);
        assertThat(a.includedCount()).isEqualTo(1);
        assertThat(a.excludedCount()).isEqualTo(1);
        assertThat(a.netDirection()).isEqualByComparingTo("0.600");
        assertThat(a.direction()).isEqualTo("POSITIVE");
        assertThat(a.uncertainty()).isEqualTo("LOW");
    }

    @Test
    void uncertainEvidenceIsFlaggedAndNeverBecomesDirectional() {
        Candidate uncertain = with(x -> new Candidate(x.publishedAt(), x.analysisAvailableAt(),
            x.analysisStatus(), x.horizon(), x.directionScore(), x.confidence(), x.materiality(),
            true, false, true));
        NewsFeaturePolicy.Aggregate onlyUncertain = NewsFeaturePolicy.aggregate(
            List.of(new Evaluated(uncertain, NewsFeaturePolicy.classify(uncertain, ASOF))));
        assertThat(onlyUncertain.netDirection()).isNull();
        assertThat(onlyUncertain.direction()).isEqualTo("UNKNOWN");
        assertThat(onlyUncertain.uncertainty()).isEqualTo("HIGH");
    }

    @Test
    void neutralBandAndNegativeDirection() {
        Candidate bearish = with(x -> new Candidate(x.publishedAt(), x.analysisAvailableAt(), x.analysisStatus(),
            x.horizon(), new BigDecimal("-0.500"), x.confidence(), x.materiality(), false, false, true));
        NewsFeaturePolicy.Aggregate a = NewsFeaturePolicy.aggregate(
            List.of(new Evaluated(bearish, NewsFeaturePolicy.classify(bearish, ASOF))));
        assertThat(a.direction()).isEqualTo("NEGATIVE");
        Candidate flat = with(x -> new Candidate(x.publishedAt(), x.analysisAvailableAt(), x.analysisStatus(),
            x.horizon(), new BigDecimal("0.100"), x.confidence(), x.materiality(), false, false, true));
        NewsFeaturePolicy.Aggregate neutral = NewsFeaturePolicy.aggregate(
            List.of(new Evaluated(flat, NewsFeaturePolicy.classify(flat, ASOF))));
        assertThat(neutral.direction()).isEqualTo("NEUTRAL");
    }
}
