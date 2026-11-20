package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoClosingSnapshotStore;
import in.aitradingsystem.marketdata.FnoMarketDataProvider;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import in.aitradingsystem.provider.upstox.UpstoxFuturesInstrumentMaster.FuturesContract;
import in.aitradingsystem.tradingrooms.FnoFuturesContractRegistry.NearFar;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Real-input invocation of the three futures research evaluators (STR-170/171/172).
 *
 * <p>Inputs come only from verified sources: contracts from the instrument-master
 * registry, live quotes from the existing shared V3 feed, and closing history from
 * the closing-snapshot store. Missing inputs are never substituted. Each evaluator
 * applies its own fail-closed checks and returns its own BLOCKED_* reason.
 *
 * <p>Rights flags default to false, so the default result is BLOCKED_RIGHTS. This
 * service never places orders, persists market data, or changes permissions.
 */
@Service
public class FnoFuturesResearchService {
    static final String NIFTY = FnoFuturesContractRegistry.NIFTY;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int HISTORY_LOOKBACK_DAYS = 14;
    private static final int ROLLOVER_SESSIONS = 3;

    /** Status plus the input counts actually supplied, for observability. */
    public record StrategyStatus(String strategyId, String status, String reasonCode,
                                 Instant evaluatedAt, String quoteSource, int contractsResolved,
                                 int freshQuotes, int historySessions, String inputState) {}

    private final ObjectProvider<UpstoxFnoV3LiveFeedService> feed;
    private final MarketSessionCalendar calendar;
    private final FnoFuturesContractRegistry registry;
    private final FnoClosingSnapshotStore store;
    private final boolean researchRightsVerified;
    private final boolean contractDiscoveryVerified;
    private final String basisUnderlyingKey;

    public FnoFuturesResearchService(ObjectProvider<UpstoxFnoV3LiveFeedService> feed,
            MarketSessionCalendar calendar, FnoFuturesContractRegistry registry,
            FnoClosingSnapshotStore store,
            @Value("${minttick.fno-research.research-rights-verified:false}") boolean researchRightsVerified,
            @Value("${minttick.fno-research.contract-discovery-verified:false}") boolean contractDiscoveryVerified,
            @Value("${minttick.fno-research.basis-underlying-key:}") String basisUnderlyingKey) {
        this.feed = feed;
        this.calendar = calendar;
        this.registry = registry;
        this.store = store;
        this.researchRightsVerified = researchRightsVerified;
        this.contractDiscoveryVerified = contractDiscoveryVerified;
        this.basisUnderlyingKey = basisUnderlyingKey == null ? "" : basisUnderlyingKey.trim();
    }

    /** Current exchange-timed live quote from the shared V3 feed, or empty. */
    Optional<UpstoxFnoV3LiveFeedService.PaperQuote> liveQuote(String instrumentKey) {
        UpstoxFnoV3LiveFeedService live = feed.getIfAvailable();
        if (live == null || instrumentKey == null) return Optional.empty();
        return live.eligiblePaperQuote(instrumentKey);
    }

    public List<StrategyStatus> evaluateAll(Instant now) {
        LocalDate today = now.atZone(IST).toLocalDate();
        boolean sessionOpen = calendar.isInsideFnoSession(now);
        return List.of(
            evaluateBasisCarry(now, today, sessionOpen),
            evaluateCalendarSpread(now, today, sessionOpen),
            evaluateRollover(now, today));
    }

    private StrategyStatus evaluateBasisCarry(Instant now, LocalDate today, boolean sessionOpen) {
        if (basisUnderlyingKey.isEmpty()) {
            var r = FnoFuturesBasisCarryV1.evaluate(null, null, null,
                new FnoFuturesBasisCarryV1.SourceRights(researchRightsVerified, researchRightsVerified,
                    sessionOpen, false), null, now);
            return status(r.strategyId(), r.status().name(), r.reasonCode(), now, "NONE", 0, 0, 0,
                "BASIS_UNDERLYING_NOT_CONFIGURED");
        }
        Optional<NearFar> pair = registry.nearFar(basisUnderlyingKey, today);
        FuturesContract future = pair.map(NearFar::near).orElse(null);
        FnoFuturesBasisCarryV1.Contract contract = future == null ? null
            : new FnoFuturesBasisCarryV1.Contract(basisUnderlyingKey, future.instrumentKey(),
                basisUnderlyingKey, future.expiry(), future.lotSize(), true,
                false); // deliverable-spot eligibility is not verified by any source here
        var spot = liveQuote(basisUnderlyingKey);
        var fut = future == null ? Optional.<UpstoxFnoV3LiveFeedService.PaperQuote>empty() : liveQuote(future.instrumentKey());
        var r = FnoFuturesBasisCarryV1.evaluate(contract, toBasisQuote(spot), toBasisQuote(fut),
            new FnoFuturesBasisCarryV1.SourceRights(researchRightsVerified, researchRightsVerified,
                sessionOpen, false), null, now);
        int resolved = future == null ? 0 : 1;
        int fresh = (spot.isPresent() ? 1 : 0) + (fut.isPresent() ? 1 : 0);
        return status(r.strategyId(), r.status().name(), r.reasonCode(), now,
            fresh > 0 ? FnoMarketDataProvider.UPSTOX_V3_LIVE.wireLabel() : "NONE",
            resolved, fresh, 0, pair.isPresent() ? "FUTURE_CONTRACT_RESOLVED" : "NO_VERIFIED_CONTRACT_PAIR");
    }

    private StrategyStatus evaluateCalendarSpread(Instant now, LocalDate today, boolean sessionOpen) {
        Optional<NearFar> pair = registry.nearFar(NIFTY, today);
        FnoFuturesCalendarSpreadV1.Contract near = pair.map(p -> toCalendarContract(p.near())).orElse(null);
        FnoFuturesCalendarSpreadV1.Contract far = pair.map(p -> toCalendarContract(p.far())).orElse(null);
        var nq = pair.flatMap(p -> liveQuote(p.near().instrumentKey())).map(FnoFuturesResearchService::toCalendarQuote);
        var fq = pair.flatMap(p -> liveQuote(p.far().instrumentKey())).map(FnoFuturesResearchService::toCalendarQuote);
        var r = FnoFuturesCalendarSpreadV1.evaluate(near, far, nq.orElse(null), fq.orElse(null),
            new FnoFuturesCalendarSpreadV1.SourceRights(researchRightsVerified, researchRightsVerified,
                contractDiscoveryVerified, sessionOpen), null, now);
        int fresh = (nq.isPresent() ? 1 : 0) + (fq.isPresent() ? 1 : 0);
        return status(r.strategyId(), r.status().name(), r.reasonCode(), now,
            fresh > 0 ? FnoMarketDataProvider.UPSTOX_V3_LIVE.wireLabel() : "NONE",
            pair.isPresent() ? 2 : 0, fresh, 0,
            pair.isPresent() ? "NEAR_FAR_CONTRACTS_RESOLVED" : "NO_VERIFIED_CONTRACT_PAIR");
    }

    private StrategyStatus evaluateRollover(Instant now, LocalDate today) {
        Optional<NearFar> pair = registry.nearFar(NIFTY, today);
        FnoFuturesRolloverSignalV1.Contract near = pair.map(p -> toRolloverContract(p.near())).orElse(null);
        FnoFuturesRolloverSignalV1.Contract far = pair.map(p -> toRolloverContract(p.far())).orElse(null);
        List<FnoFuturesRolloverSignalV1.CompletedSession> sessions = pair.isPresent()
            ? completedSessions(pair.get(), today) : List.of();
        var r = FnoFuturesRolloverSignalV1.evaluate(near, far, sessions,
            new FnoFuturesRolloverSignalV1.SourceRights(researchRightsVerified, researchRightsVerified,
                contractDiscoveryVerified), null, now);
        return status(r.strategyId(), r.status().name(), r.reasonCode(), now,
            sessions.isEmpty() ? "NONE" : sessions.get(0).near().source(),
            pair.isPresent() ? 2 : 0, 0, sessions.size(),
            pair.isPresent() ? "NEAR_FAR_CONTRACTS_RESOLVED" : "NO_VERIFIED_CONTRACT_PAIR");
    }

    /** Last up to three sessions where BOTH legs are stored with OI and volume, oldest first. */
    List<FnoFuturesRolloverSignalV1.CompletedSession> completedSessions(NearFar pair, LocalDate today) {
        var rows = store.pairRows(pair.near().instrumentKey(), pair.far().instrumentKey(),
            today.minusDays(HISTORY_LOOKBACK_DAYS), today);
        Map<LocalDate, Map<String, FnoClosingSnapshotStore.StoredRow>> byDate = new TreeMap<>();
        for (var row : rows) {
            byDate.computeIfAbsent(row.sessionDate(), d -> new TreeMap<>()).put(row.instrumentKey(), row);
        }
        List<FnoFuturesRolloverSignalV1.CompletedSession> complete = new ArrayList<>();
        for (var entry : byDate.entrySet()) {
            var near = entry.getValue().get(pair.near().instrumentKey());
            var far = entry.getValue().get(pair.far().instrumentKey());
            if (near == null || far == null) continue;
            if (!near.providerLabel().equals(far.providerLabel())) continue;
            if (near.openInterestUnits() == null || far.openInterestUnits() == null
                    || near.sessionVolumeUnits() == null || far.sessionVolumeUnits() == null) continue;
            complete.add(new FnoFuturesRolloverSignalV1.CompletedSession(entry.getKey(), true,
                toLeg(near), toLeg(far)));
        }
        int from = Math.max(0, complete.size() - ROLLOVER_SESSIONS);
        return List.copyOf(complete.subList(from, complete.size()));
    }

    private static FnoFuturesRolloverSignalV1.LegSnapshot toLeg(FnoClosingSnapshotStore.StoredRow row) {
        return new FnoFuturesRolloverSignalV1.LegSnapshot(row.instrumentKey(), row.providerLabel(),
            row.exchangeAt(), row.observedAt(), row.openInterestUnits(), row.sessionVolumeUnits(),
            row.bid(), row.ask());
    }

    private static FnoFuturesCalendarSpreadV1.Contract toCalendarContract(FuturesContract c) {
        return new FnoFuturesCalendarSpreadV1.Contract(c.instrumentKey(), c.underlyingKey(),
            c.expiry(), c.lotSize(), true);
    }

    private static FnoFuturesRolloverSignalV1.Contract toRolloverContract(FuturesContract c) {
        return new FnoFuturesRolloverSignalV1.Contract(c.instrumentKey(), c.underlyingKey(),
            c.expiry(), c.lotSize(), true);
    }

    private static FnoFuturesCalendarSpreadV1.Quote toCalendarQuote(
            UpstoxFnoV3LiveFeedService.PaperQuote q) {
        return new FnoFuturesCalendarSpreadV1.Quote(q.instrumentKey(),
            FnoMarketDataProvider.UPSTOX_V3_LIVE.wireLabel(), q.bid(), q.ask(),
            q.exchangeAt(), q.observedAt());
    }

    private static FnoFuturesBasisCarryV1.Quote toBasisQuote(Optional<UpstoxFnoV3LiveFeedService.PaperQuote> q) {
        return q.map(v -> new FnoFuturesBasisCarryV1.Quote(v.bid(), v.ask(), v.exchangeAt(), v.observedAt()))
            .orElse(null);
    }

    private static StrategyStatus status(String id, String status, String reason, Instant now,
            String source, int contracts, int fresh, int history, String inputState) {
        return new StrategyStatus(id, status, reason, now, source, contracts, fresh, history, inputState);
    }
}
