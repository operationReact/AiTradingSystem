package in.aitradingsystem.news.feature;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.news.feature.NewsShadowEvaluator.Observation;
import in.aitradingsystem.news.feature.NewsShadowEvaluator.Provenance;
import in.aitradingsystem.news.feature.NewsShadowEvaluator.Report;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SHADOW evaluator tests over FIXTURE_LABELLED observations. These prove the arithmetic, the leakage
 * exclusion and the fail-closed flags. They say nothing about whether news improves any strategy.
 */
class NewsShadowEvaluatorTest {
    private static final Instant SIGNAL = Instant.parse("2026-11-24T10:00:00Z");

    /** Context with a single directional label, or none. asOf and any evidence are supplied by the caller. */
    private static NewsContext context(String direction, Instant asOf, int included) {
        boolean directional = !"UNKNOWN".equals(direction);
        return new NewsContext(NewsFeaturePolicy.CONTRACT, 1L, "X-EQ", asOf,
            included > 0 ? NewsContext.STATUS_INCLUDED_EVIDENCE : NewsContext.STATUS_NO_COVERAGE,
            directional ? new BigDecimal("0.5") : null, direction, "LOW", included, 0, List.of(),
            NewsContext.LIMITATIONS, false, null, null);
    }

    private static Observation obs(int strategyDirection, String returnPct, String newsDirection, int included) {
        return new Observation(SIGNAL, strategyDirection, new BigDecimal(returnPct),
            context(newsDirection, SIGNAL.minusSeconds(60), included));
    }

    @Test
    void aligningNewsCohortHitRateMinusNoNewsIsReported() {
        List<Observation> fixtures = new ArrayList<>();
        // Long strategy, positive news: 3 of 4 hit.
        fixtures.add(obs(1, "0.01", "POSITIVE", 1));
        fixtures.add(obs(1, "0.02", "POSITIVE", 1));
        fixtures.add(obs(1, "0.03", "POSITIVE", 1));
        fixtures.add(obs(1, "-0.01", "POSITIVE", 1));
        // Long strategy, no news: 1 of 4 hit.
        fixtures.add(obs(1, "0.01", "UNKNOWN", 0));
        fixtures.add(obs(1, "-0.01", "UNKNOWN", 0));
        fixtures.add(obs(1, "-0.02", "UNKNOWN", 0));
        fixtures.add(obs(1, "-0.03", "UNKNOWN", 0));

        Report r = NewsShadowEvaluator.evaluate(fixtures, Provenance.FIXTURE_LABELLED);
        assertThat(r.cohorts().get(0).name()).isEqualTo("NEWS_ALIGNED");
        assertThat(r.cohorts().get(0).count()).isEqualTo(4);
        assertThat(r.cohorts().get(0).hitRate()).isEqualByComparingTo("0.7500");
        assertThat(r.cohorts().get(2).name()).isEqualTo("NO_INCLUDED_NEWS");
        assertThat(r.cohorts().get(2).hitRate()).isEqualByComparingTo("0.2500");
        assertThat(r.incrementalHitRate()).isEqualByComparingTo("0.5000");
    }

    @Test
    void opposedNewsIsItsOwnCohortAndShortStrategiesAreSignedCorrectly() {
        List<Observation> fixtures = List.of(
            obs(-1, "-0.02", "POSITIVE", 1),  // short + positive news = opposed; short wins 0.02
            obs(-1, "0.01", "NEGATIVE", 1));  // short + negative news = aligned; short loses 0.01
        Report r = NewsShadowEvaluator.evaluate(fixtures, Provenance.FIXTURE_LABELLED);
        assertThat(r.cohorts().get(1).name()).isEqualTo("NEWS_OPPOSED");
        assertThat(r.cohorts().get(1).count()).isEqualTo(1);
        assertThat(r.cohorts().get(1).hitRate()).isEqualByComparingTo("1.0000");
        assertThat(r.cohorts().get(0).name()).isEqualTo("NEWS_ALIGNED");
        assertThat(r.cohorts().get(0).hitRate()).isEqualByComparingTo("0.0000");
    }

    @Test
    void observationsWithLookaheadAreExcludedAndCounted() {
        // Context created after the signal: future-informed, must be excluded.
        Observation future = new Observation(SIGNAL, 1, new BigDecimal("0.05"),
            context("POSITIVE", SIGNAL.plusSeconds(30), 1));
        // Evidence published after the signal inside an otherwise-valid context.
        NewsContext.Evidence leaked = new NewsContext.Evidence("a", "SRC", "t", "u",
            SIGNAL.plusSeconds(10), SIGNAL.minusSeconds(60), SIGNAL.plusSeconds(86_400),
            "INTRADAY", "POSITIVE", new BigDecimal("0.5"), new BigDecimal("0.8"), new BigDecimal("0.7"),
            "TEST", "m", "s", "INCLUDED", true, "GENERAL", "FRESH", null);
        Observation leakedEvidence = new Observation(SIGNAL, 1, new BigDecimal("0.05"),
            new NewsContext(NewsFeaturePolicy.CONTRACT, 1L, "X-EQ", SIGNAL.minusSeconds(60),
                NewsContext.STATUS_INCLUDED_EVIDENCE, new BigDecimal("0.5"), "POSITIVE", "LOW", 1, 0,
                List.of(leaked), NewsContext.LIMITATIONS, false, null, null));
        Observation clean = obs(1, "0.01", "POSITIVE", 1);

        Report r = NewsShadowEvaluator.evaluate(List.of(future, leakedEvidence, clean), Provenance.FIXTURE_LABELLED);
        assertThat(r.observations()).isEqualTo(3);
        assertThat(r.leakageExcluded()).isEqualTo(2);
        assertThat(r.cohorts().get(0).count()).isEqualTo(1);
    }

    @Test
    void noReportCanInfluencePaperOrLiveAndExecutionStaysDisabled() {
        Report r = NewsShadowEvaluator.evaluate(List.of(obs(1, "0.01", "POSITIVE", 1)), Provenance.REAL_MARKET_AND_NEWS);
        assertThat(r.promotionEligible()).isFalse();
        assertThat(r.paperInfluenceAllowed()).isFalse();
        assertThat(r.liveInfluenceAllowed()).isFalse();
        assertThat(r.executionEnabled()).isFalse();
        assertThat(r.paperOrderSubmitted()).isFalse();
        assertThat(r.liveMoney()).isFalse();
        assertThat(r.minimumSampleForPromotion()).isEqualTo(NewsShadowEvaluator.MINIMUM_SAMPLE_FOR_PROMOTION);
    }

    @Test
    void fixtureReportIsLabelledAndNeverPresentedAsEvidence() {
        Report fixture = NewsShadowEvaluator.evaluate(List.of(obs(1, "0.01", "POSITIVE", 1)), Provenance.FIXTURE_LABELLED);
        assertThat(fixture.limitations().get(0)).startsWith("FIXTURE_LABELLED");
        Report real = NewsShadowEvaluator.evaluate(List.of(obs(1, "0.01", "POSITIVE", 1)), Provenance.REAL_MARKET_AND_NEWS);
        assertThat(real.limitations()).noneMatch(l -> l.startsWith("FIXTURE_LABELLED"));
    }

    @Test
    void emptyInputsDoNotProduceFakeMetrics() {
        Report r = NewsShadowEvaluator.evaluate(List.of(), Provenance.FIXTURE_LABELLED);
        assertThat(r.observations()).isZero();
        assertThat(r.incrementalHitRate()).isNull();
        assertThat(r.cohorts()).allSatisfy(c -> {
            assertThat(c.count()).isZero();
            assertThat(c.hitRate()).isNull();
        });
    }
}
