package in.aitradingsystem.provider.upstox;

import java.time.LocalDate;
import java.util.List;

/**
 * Non-persisting, sanitized operator-side F&O source observation.
 * This is deliberately NOT a controller: an authenticated ADMIN-only invocation
 * must be wired and reviewed separately before any runtime source probe.
 * No contracts, quotes, tokens, instrument keys or credentials are emitted.
 */
final class UpstoxFnoProbeSummary {
    private UpstoxFnoProbeSummary() {}

    record Receipt(String status, int contracts, int strikes, int quotedLegs,
                   int legsWithGreeks, boolean exchangeQuoteTimeVerified) {}

    static Receipt summarize(List<UpstoxAnalyticsSource.FnoOptionContract> contracts,
                             UpstoxAnalyticsSource.OptionChainSnapshot chain) {
        if (contracts == null || chain == null || chain.strikes() == null
                || contracts.size() > 1024 || chain.strikes().size() > 1024
                || !"UPSTOX_ANALYTICS".equals(chain.source())) {
            throw new IllegalArgumentException("UPSTOX_FNO_PROBE_INVALID");
        }
        int quotes = 0;
        int greeks = 0;
        for (var strike : chain.strikes()) {
            if (strike == null || strike.call() == null || strike.put() == null) {
                throw new IllegalArgumentException("UPSTOX_FNO_PROBE_INVALID");
            }
            for (var leg : List.of(strike.call(), strike.put())) {
                if (leg.ltp() != null) quotes++;
                if (leg.iv() != null || leg.delta() != null || leg.gamma() != null
                        || leg.theta() != null || leg.vega() != null) greeks++;
            }
        }
        return new Receipt(contracts.isEmpty() && chain.strikes().isEmpty()
                ? "OBSERVED_EMPTY" : "OBSERVED_NONEMPTY",
                contracts.size(), chain.strikes().size(), quotes, greeks,
                chain.exchangeQuoteTimeVerified());
    }

    /** Safe failure receipt; never include exception messages or upstream bodies. */
    static Receipt unavailable() {
        return new Receipt("SOURCE_UNAVAILABLE", 0, 0, 0, 0, false);
    }
}
