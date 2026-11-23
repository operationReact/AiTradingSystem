package in.aitradingsystem.news.feature;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SHADOW-only comparison of a strategy's directional calls with and without as-of news context.
 *
 * <p>This is research measurement. It never influences PAPER ranking, LIVE execution, risk, sizing or
 * orders. Every report carries executionEnabled=false, paperOrderSubmitted=false and liveMoney=false,
 * and promotionEligible/paperInfluenceAllowed are always false. A report from fixture data is labelled
 * FIXTURE_LABELLED and is not evidence about markets or news.
 *
 * <p>Leakage: an observation is excluded when its context is as of a later instant than its signal, or
 * when any evidence in it was published or analyzed after the signal. Excluded rows are counted.
 */
public final class NewsShadowEvaluator {
    public static final String CONTRACT = "news.shadow-evaluation.v1";
    /** Minimum observations before any future promotion could even be considered. */
    public static final int MINIMUM_SAMPLE_FOR_PROMOTION = 200;

    private NewsShadowEvaluator() {}

    public enum Provenance { REAL_MARKET_AND_NEWS, FIXTURE_LABELLED }

    /** One historical signal: strategy direction (+1 long, -1 short), realised forward return, and as-of news. */
    public record Observation(Instant signalAt, int strategyDirection, BigDecimal forwardReturn, NewsContext context) {}

    public record Cohort(String name, int count, BigDecimal hitRate, BigDecimal meanStrategyReturn) {}

    public record Report(
        String contract,
        Provenance provenance,
        int observations,
        int leakageExcluded,
        List<Cohort> cohorts,
        BigDecimal incrementalHitRate,
        int minimumSampleForPromotion,
        boolean promotionEligible,
        boolean paperInfluenceAllowed,
        boolean liveInfluenceAllowed,
        boolean executionEnabled,
        boolean paperOrderSubmitted,
        boolean liveMoney,
        List<String> limitations) {}

    public static Report evaluate(List<Observation> observations, Provenance provenance) {
        if (observations == null) throw new IllegalArgumentException("observations are required");
        if (provenance == null) throw new IllegalArgumentException("provenance is required");
        int leakage = 0;
        List<Observation> kept = new ArrayList<>();
        for (Observation o : observations) {
            if (o.context().asOf().isAfter(o.signalAt()) || !NewsBacktestAnnotator.leakageViolations(o.context()).isEmpty()) {
                leakage++;
                continue;
            }
            kept.add(o);
        }

        Cohort aligned = cohort("NEWS_ALIGNED", kept, o -> alignment(o) > 0);
        Cohort opposed = cohort("NEWS_OPPOSED", kept, o -> alignment(o) < 0);
        Cohort none = cohort("NO_INCLUDED_NEWS", kept, o -> alignment(o) == 0 && !hasIncluded(o));
        Cohort neutral = cohort("NEWS_NEUTRAL", kept, o -> alignment(o) == 0 && hasIncluded(o));

        BigDecimal incremental = null;
        if (aligned.count() > 0 && none.count() > 0 && aligned.hitRate() != null && none.hitRate() != null) {
            incremental = aligned.hitRate().subtract(none.hitRate());
        }

        List<String> limitations = new ArrayList<>(List.of(
            "Shadow measurement only. It does not change PAPER ranking, LIVE execution, risk, sizing or orders.",
            "Promotion is not eligible: requires real provenance, pre-specified out-of-time validation, costs and a minimum sample.",
            "No survivorship control is applied here. Universe membership must be supplied as-of by the caller (BLOCKED).",
            "Leakage-excluded observations are counted and never used."));
        if (provenance == Provenance.FIXTURE_LABELLED) {
            limitations.add(0, "FIXTURE_LABELLED: observations are synthetic fixtures. This report is not evidence about markets or news.");
        }

        return new Report(CONTRACT, provenance, observations.size(), leakage,
            List.of(aligned, opposed, none, neutral), incremental, MINIMUM_SAMPLE_FOR_PROMOTION,
            false, false, false, false, false, false, List.copyOf(limitations));
    }

    /** +1 when the news direction agrees with the strategy, -1 when it disagrees, 0 when there is no directional news. */
    static int alignment(Observation o) {
        NewsContext c = o.context();
        if (c.netDirection() == null) return 0;
        int news = switch (c.direction()) {
            case "POSITIVE" -> 1;
            case "NEGATIVE" -> -1;
            default -> 0;
        };
        if (news == 0) return 0;
        return news == Integer.signum(o.strategyDirection()) ? 1 : -1;
    }

    static boolean hasIncluded(Observation o) {
        return o.context().includedCount() > 0;
    }

    private static Cohort cohort(String name, List<Observation> rows, java.util.function.Predicate<Observation> in) {
        int count = 0;
        int hits = 0;
        BigDecimal sum = BigDecimal.ZERO;
        for (Observation o : rows) {
            if (!in.test(o)) continue;
            count++;
            BigDecimal strategyReturn = o.forwardReturn().multiply(BigDecimal.valueOf(o.strategyDirection()));
            sum = sum.add(strategyReturn);
            if (strategyReturn.signum() > 0) hits++;
        }
        if (count == 0) return new Cohort(name, 0, null, null);
        BigDecimal hitRate = BigDecimal.valueOf(hits).divide(BigDecimal.valueOf(count), 4, java.math.RoundingMode.HALF_UP);
        BigDecimal mean = sum.divide(BigDecimal.valueOf(count), 6, java.math.RoundingMode.HALF_UP);
        return new Cohort(name, count, hitRate, mean);
    }
}
