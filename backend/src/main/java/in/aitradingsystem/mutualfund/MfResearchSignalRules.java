package in.aitradingsystem.mutualfund;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Explicit research rule, not prediction/valuation: review only when a fund's
 * NAV newly crosses a category-specific 20-observation peak drawdown.
 * 22 verified sessions required; no signals from missing/stale data.
 */
public final class MfResearchSignalRules {
    public record Observation(LocalDate date, BigDecimal nav) {}
    public record Signal(String kind, int drawdownBps, BigDecimal peakNav) {}
    private MfResearchSignalRules() {}

    public static Signal evaluate(MfResearchCatalogue.Fund fund, List<Observation> observations) {
        if (fund.dipThresholdBps() <= 0 || observations == null) return null;
        List<Observation> data = new ArrayList<>(observations);
        data.sort(Comparator.comparing(Observation::date));
        if (data.size() < 22) return null;
        Observation latest = data.get(data.size() - 1);
        Observation previous = data.get(data.size() - 2);
        if (!latest.date().isAfter(previous.date())) return null;
        int current = drawdown(data, data.size() - 1);
        int prior = drawdown(data, data.size() - 2);
        int normal = fund.dipThresholdBps();
        int deep = normal * 2;
        if (current >= deep && prior < deep)
            return new Signal("DEEP_PULLBACK_REVIEW", current, peak(data, data.size() - 1));
        if (current >= normal && prior < normal)
            return new Signal("PULLBACK_REVIEW", current, peak(data, data.size() - 1));
        return null;
    }

    private static int drawdown(List<Observation> data, int index) {
        BigDecimal top = peak(data, index);
        if (top.signum() <= 0 || data.get(index).nav().signum() <= 0) return 0;
        return top.subtract(data.get(index).nav()).max(BigDecimal.ZERO)
            .multiply(BigDecimal.valueOf(10_000))
            .divide(top, 0, RoundingMode.HALF_UP).intValue();
    }

    private static BigDecimal peak(List<Observation> data, int index) {
        BigDecimal top = BigDecimal.ZERO;
        for (int i = index - 20; i < index; i++) {
            if (i < 0) continue;
            top = top.max(data.get(i).nav());
        }
        return top;
    }
}
