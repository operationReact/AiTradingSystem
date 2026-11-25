package in.aitradingsystem.news.feature;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Pure, side-effect-free rules that decide whether one analyzed news item may contribute to a
 * Mind research decision at a given as-of instant.
 *
 * <p>Timestamps are the contract: an item is usable at {@code asOf} only when the article was
 * published at or before {@code asOf} AND the analysis row was created at or before {@code asOf}.
 * The analysis creation time, not the article ingest time, is the moment the classification became
 * knowable. Unknown, stale, unverified, uncertain or failed evidence never adds directional weight.
 */
public final class NewsFeaturePolicy {
    public static final String CONTRACT = "news.context.v1";
    /** Longest validity window used by any horizon; also the bounded lookback for queries. */
    public static final Duration LOOKBACK = Duration.ofDays(30);
    public static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.50");
    /** Net direction within +/- this band is reported as NEUTRAL. */
    public static final BigDecimal NEUTRAL_BAND = new BigDecimal("0.20");

    private NewsFeaturePolicy() {}

    /** Why one analyzed item was included or excluded. Every exclusion is explicit and auditable. */
    public enum Status {
        INCLUDED,
        EXCLUDED_PUBLISHED_AFTER_AS_OF,
        EXCLUDED_ANALYSIS_NOT_YET_AVAILABLE,
        EXCLUDED_UNVERIFIED_INSTRUMENT,
        EXCLUDED_ANALYSIS_FAILED,
        EXCLUDED_EXPIRED,
        EXCLUDED_LOW_CONFIDENCE,
        EXCLUDED_INVALID_SCORE,
        EXCLUDED_UNCERTAIN
    }

    /** Analyzed-news facts relevant to one instrument, already loaded from persistence. */
    public record Candidate(
        Instant publishedAt,
        Instant analysisAvailableAt,
        String analysisStatus,
        String horizon,
        BigDecimal directionScore,
        BigDecimal confidence,
        BigDecimal materiality,
        boolean contradiction,
        boolean ambiguity,
        boolean symbolVerified) {}

    /** Result of evaluating one candidate at one as-of instant. */
    public record Evaluated(Candidate candidate, Status status) {}

    /** Aggregate directional evidence. Net direction is null when no weighted evidence was included. */
    public record Aggregate(BigDecimal netDirection, String direction, String uncertainty,
                            int includedCount, int excludedCount) {}

    /**
     * Validity end for an item. Unknown horizons use the shortest window (conservative): stale
     * classifications must not persist as current evidence.
     */
    public static Instant validUntil(Instant publishedAt, String horizon) {
        return publishedAt.plus(switch (horizon == null ? "UNKNOWN" : horizon) {
            case "LONGER_TERM" -> Duration.ofDays(30);
            case "MULTI_DAY" -> Duration.ofDays(5);
            default -> Duration.ofDays(1);
        });
    }

    /** Evaluates one candidate. The order is fixed so the first matching exclusion is reported. */
    public static Status classify(Candidate c, Instant asOf) {
        if (c.publishedAt().isAfter(asOf)) return Status.EXCLUDED_PUBLISHED_AFTER_AS_OF;
        if (c.analysisAvailableAt() == null || c.analysisAvailableAt().isAfter(asOf)) {
            return Status.EXCLUDED_ANALYSIS_NOT_YET_AVAILABLE;
        }
        // A failed analysis has no trustworthy symbol mapping, so its failure is the reported reason.
        if (!"ANALYZED".equals(c.analysisStatus())) return Status.EXCLUDED_ANALYSIS_FAILED;
        if (!c.symbolVerified()) return Status.EXCLUDED_UNVERIFIED_INSTRUMENT;
        if (!asOf.isBefore(validUntil(c.publishedAt(), c.horizon()))) return Status.EXCLUDED_EXPIRED;
        if (c.confidence() == null || c.materiality() == null || c.directionScore() == null
            || c.confidence().compareTo(MIN_CONFIDENCE) < 0) {
            return Status.EXCLUDED_LOW_CONFIDENCE;
        }
        // Treat all model-produced scores as untrusted, including persisted/legacy rows.
        // Invalid ranges must never flip aggregate direction or produce a net outside [-1, 1].
        if (c.confidence().compareTo(BigDecimal.ONE) > 0
            || c.materiality().signum() <= 0
            || c.materiality().compareTo(BigDecimal.ONE) > 0
            || c.directionScore().abs().compareTo(BigDecimal.ONE) > 0) {
            return Status.EXCLUDED_INVALID_SCORE;
        }
        if (c.contradiction() || c.ambiguity()) return Status.EXCLUDED_UNCERTAIN;
        return Status.INCLUDED;
    }

    /**
     * Confidence- and materiality-weighted direction over INCLUDED items only. Uncertainty is
     * LOW when nothing was excluded for uncertainty, MEDIUM when some was, HIGH when every item
     * was uncertain or excluded, and UNKNOWN when there is no evidence at all.
     */
    public static Aggregate aggregate(List<Evaluated> evaluated) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightTotal = BigDecimal.ZERO;
        int included = 0;
        int uncertainExcluded = 0;
        for (Evaluated e : evaluated) {
            if (e.status() == Status.EXCLUDED_UNCERTAIN) uncertainExcluded++;
            if (e.status() != Status.INCLUDED) continue;
            included++;
            BigDecimal weight = e.candidate().confidence().multiply(e.candidate().materiality());
            weightedSum = weightedSum.add(weight.multiply(e.candidate().directionScore()));
            weightTotal = weightTotal.add(weight);
        }
        int excluded = evaluated.size() - included;
        String uncertainty;
        if (evaluated.isEmpty() || included == 0 && uncertainExcluded == 0) {
            uncertainty = "UNKNOWN";
        } else if (included == 0 || uncertainExcluded == evaluated.size()) {
            uncertainty = "HIGH";
        } else if (uncertainExcluded > 0) {
            uncertainty = "MEDIUM";
        } else {
            uncertainty = "LOW";
        }
        if (included == 0 || weightTotal.signum() == 0) {
            return new Aggregate(null, "UNKNOWN", uncertainty, included, excluded);
        }
        BigDecimal net = weightedSum.divide(weightTotal, 3, RoundingMode.HALF_UP);
        String direction = net.compareTo(NEUTRAL_BAND) > 0 ? "POSITIVE"
            : net.compareTo(NEUTRAL_BAND.negate()) < 0 ? "NEGATIVE" : "NEUTRAL";
        return new Aggregate(net, direction, uncertainty, included, excluded);
    }
}
