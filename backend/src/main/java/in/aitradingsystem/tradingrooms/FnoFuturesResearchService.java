package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoMarketDataProvider;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Read-only research invocation of the three futures evaluators (STR-170/171/172).
 *
 * <p>Each evaluator makes its own fail-closed decision. This service only supplies
 * inputs and reports results. Rights come from operator flags that default to false,
 * so the default result is BLOCKED_RIGHTS for every strategy. Quotes come only from the
 * existing V3 feed (no separate socket). Session history comes from the closing-snapshot
 * store (not yet read by this service), so STR-172 reports BLOCKED_DATA.
 *
 * <p>Never places orders, never persists, never changes trading or display permissions.
 */
@Service
public class FnoFuturesResearchService {
    public record StrategyStatus(String strategyId, String status, String reasonCode,
                                 Instant evaluatedAt, String quoteSource) {}

    private final ObjectProvider<UpstoxFnoV3LiveFeedService> feed;
    private final MarketSessionCalendar calendar;
    private final boolean researchRightsVerified;
    private final boolean contractDiscoveryVerified;

    public FnoFuturesResearchService(ObjectProvider<UpstoxFnoV3LiveFeedService> feed,
            MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.research-rights-verified:false}") boolean researchRightsVerified,
            @Value("${minttick.fno-research.contract-discovery-verified:false}") boolean contractDiscoveryVerified) {
        this.feed = feed;
        this.calendar = calendar;
        this.researchRightsVerified = researchRightsVerified;
        this.contractDiscoveryVerified = contractDiscoveryVerified;
    }

    /** Exchange-timed V3 quote for an NSE_FO key, labelled with the canonical source. */
    Optional<FnoFuturesCalendarSpreadV1.Quote> v3Quote(String instrumentKey) {
        UpstoxFnoV3LiveFeedService live = feed.getIfAvailable();
        if (live == null || instrumentKey == null) return Optional.empty();
        return live.eligiblePaperQuote(instrumentKey).map(q -> new FnoFuturesCalendarSpreadV1.Quote(
            q.instrumentKey(), FnoMarketDataProvider.UPSTOX_V3_LIVE.wireLabel(),
            q.bid(), q.ask(), q.exchangeAt(), q.observedAt()));
    }

    /**
     * Runs all three evaluators as of {@code now} with the inputs currently available.
     * No contract registry or session store is wired yet, so contract, quote and history
     * inputs are absent and the evaluators return their own BLOCKED_* reasons.
     */
    public List<StrategyStatus> evaluateAll(Instant now) {
        boolean sessionOpen = calendar.isInsideFnoSession(now);
        var str170 = FnoFuturesBasisCarryV1.evaluate(null, null, null,
            new FnoFuturesBasisCarryV1.SourceRights(researchRightsVerified, researchRightsVerified,
                sessionOpen, false), null, now);
        var str171 = FnoFuturesCalendarSpreadV1.evaluate(null, null, null, null,
            new FnoFuturesCalendarSpreadV1.SourceRights(researchRightsVerified, researchRightsVerified,
                contractDiscoveryVerified, sessionOpen), null, now);
        var str172 = FnoFuturesRolloverSignalV1.evaluate(null, null, List.of(),
            new FnoFuturesRolloverSignalV1.SourceRights(researchRightsVerified, researchRightsVerified,
                contractDiscoveryVerified), null, now);
        return List.of(
            new StrategyStatus(str170.strategyId(), str170.status().name(), str170.reasonCode(),
                now, "NONE"),
            new StrategyStatus(str171.strategyId(), str171.status().name(), str171.reasonCode(),
                now, "NONE"),
            new StrategyStatus(str172.strategyId(), str172.status().name(), str172.reasonCode(),
                now, "NONE"));
    }

}
