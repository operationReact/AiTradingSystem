package in.aitradingsystem.news.feature;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Shadow-only historical annotation: attaches the as-of news context to each historical signal
 * time and audits it for future-data leakage. It never changes a backtest's trades, metrics or
 * decisions, and it does not claim incremental value. Incremental value is BLOCKED until a
 * rights-approved historical news corpus and a pre-specified out-of-time validation exist.
 */
@Service
public class NewsBacktestAnnotator {
    public static final String CONTRACT = "news.backtest-shadow.v1";
    public static final int MAX_SIGNALS = 500;
    static final List<String> LIMITATIONS = List.of(
        "Shadow annotation only. Trades, metrics and decisions of the backtest are unchanged.",
        "No incremental value is claimed. Walk-forward validation against a no-news baseline is BLOCKED.",
        "Coverage depends on which articles were ingested and analyzed before each signal time.");

    private final NewsFeatureService features;

    public NewsBacktestAnnotator(NewsFeatureService features) {
        this.features = features;
    }

    public record SignalAnnotation(Instant signalAt, NewsContext context, List<String> leakageViolations) {}

    public record ShadowSummary(String contract, int signals, int withIncludedNews, int withoutIncludedNews,
                                int positiveEvidence, int negativeEvidence, int neutralEvidence,
                                int leakageViolationCount, Map<String, Integer> exclusionReasons,
                                List<String> limitations) {}

    public List<SignalAnnotation> annotate(long instrumentId, String tradingSymbol, List<Instant> signalTimes) {
        if (signalTimes == null) throw new IllegalArgumentException("signalTimes is required");
        if (signalTimes.size() > MAX_SIGNALS) {
            throw new IllegalArgumentException("signalTimes must not exceed " + MAX_SIGNALS);
        }
        List<SignalAnnotation> out = new ArrayList<>(signalTimes.size());
        for (Instant signalAt : signalTimes) {
            if (signalAt == null) throw new IllegalArgumentException("signal time is required");
            NewsContext context = features.contextFor(instrumentId, tradingSymbol, signalAt);
            out.add(new SignalAnnotation(signalAt, context, leakageViolations(context)));
        }
        return List.copyOf(out);
    }

    public ShadowSummary summarize(List<SignalAnnotation> annotations) {
        int included = 0;
        int positive = 0;
        int negative = 0;
        int neutral = 0;
        int violations = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (SignalAnnotation a : annotations) {
            NewsContext c = a.context();
            violations += a.leakageViolations().size();
            if (c.includedCount() > 0) included++;
            if ("POSITIVE".equals(c.direction())) positive++;
            if ("NEGATIVE".equals(c.direction())) negative++;
            if ("NEUTRAL".equals(c.direction())) neutral++;
            for (NewsContext.Evidence e : c.evidence()) {
                if (!e.included()) reasons.merge(e.status(), 1, Integer::sum);
            }
        }
        return new ShadowSummary(CONTRACT, annotations.size(), included, annotations.size() - included,
            positive, negative, neutral, violations, reasons, LIMITATIONS);
    }

    /**
     * Independent audit of one context against its own as-of instant. Any evidence that was
     * published or analyzed after the signal time is reported. Empty means no lookahead.
     */
    static List<String> leakageViolations(NewsContext context) {
        List<String> violations = new ArrayList<>();
        Instant asOf = context.asOf();
        for (NewsContext.Evidence e : context.evidence()) {
            if (e.publishedAt() != null && e.publishedAt().isAfter(asOf)) {
                violations.add("published after as-of: " + e.articleId());
            }
            if (e.analysisAvailableAt() != null && e.analysisAvailableAt().isAfter(asOf)) {
                violations.add("analysis created after as-of: " + e.articleId());
            }
            if (e.included() && e.validUntil() != null && !asOf.isBefore(e.validUntil())) {
                violations.add("included after validity expired: " + e.articleId());
            }
        }
        return List.copyOf(violations);
    }
}
