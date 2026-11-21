package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoClosingSnapshotStore;
import in.aitradingsystem.marketdata.FnoCashFuturesFeasibilityStore;
import in.aitradingsystem.marketdata.FnoVerifiedCompletedOhlcvOiStore;
import in.aitradingsystem.marketdata.FnoIndexHedgeResearchStore;
import in.aitradingsystem.marketdata.FnoVerifiedIntradayLiquidityStore;
import in.aitradingsystem.marketdata.FnoVerifiedExpiryBasisPairStore;
import in.aitradingsystem.marketdata.FnoMarketDataProvider;
import in.aitradingsystem.marketdata.FnoVerifiedFinalPriceOiSessionStore;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Read-only invocation of eleven futures research evaluators (STR-170..180).
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
    private static final int OI_LOOKBACK_DAYS = 21;
    // Original deterministic source-quality fixture, not trading performance calibration.
    private static final FnoContractLiquidityGateV1.Thresholds LIQUIDITY_THRESHOLDS =
        new FnoContractLiquidityGateV1.Thresholds(100, 20, 2, 20, 50, 5, 10, 30, 500);
    // Experimental pattern parameters; require external regime and cost calibration.
    private static final FnoFuturesMeanReversionV1.Thresholds MEAN_REVERSION_THRESHOLDS =
        new FnoFuturesMeanReversionV1.Thresholds(25, 200, 60, 100, 25, 9000,
            1500, 1000, 900, 5000);
    // Research-only fixture parameters; must be calibrated out-of-sample.
    private static final FnoFuturesMomentumV1.Thresholds MOMENTUM_THRESHOLDS =
        new FnoFuturesMomentumV1.Thresholds(100, 2, 11000, 2000, 2500, 800, 20, 30);
    // Research fixture calibration, not empirically approved thresholds.
    private static final FnoFuturesPriceOiDivergenceV1.Thresholds PRICE_OI_THRESHOLDS =
        new FnoFuturesPriceOiDivergenceV1.Thresholds(500, 500, 3, 2500, 2500, 5000);
    // Experimental research calibration, never permission for PAPER/LIVE orders.
    private static final FnoFuturesOpenInterestTrendV1.Thresholds OI_THRESHOLDS =
        new FnoFuturesOpenInterestTrendV1.Thresholds(800, 3, 2500, 5000);

    /** Status plus the input counts actually supplied, for observability. */
    public record StrategyStatus(String strategyId, String status, String reasonCode,
                                 Instant evaluatedAt, String quoteSource, int contractsResolved,
                                 int freshQuotes, int historySessions, String inputState) {}

    private final ObjectProvider<UpstoxFnoV3LiveFeedService> feed;
    private final MarketSessionCalendar calendar;
    private final FnoFuturesContractRegistry registry;
    private final FnoClosingSnapshotStore store;
    private final FnoVerifiedFinalPriceOiSessionStore finalSessionStore;
    private final FnoCashFuturesFeasibilityStore cashFeasibilityStore;
    private final FnoVerifiedCompletedOhlcvOiStore completedOhlcvOiStore;
    private final FnoIndexHedgeResearchStore indexHedgeStore;
    private final FnoVerifiedIntradayLiquidityStore liquidityStore;
    private final FnoVerifiedExpiryBasisPairStore expiryBasisStore;
    private final boolean researchRightsVerified;
    private final boolean contractDiscoveryVerified;
    private final boolean finalPriceResearchRightsVerified;
    private final boolean cashFeasibilityResearchRightsVerified;
    private final boolean completedOhlcvResearchRightsVerified;
    private final boolean hedgeResearchRightsVerified;
    private final boolean liquidityResearchRightsVerified;
    private final boolean expiryBasisResearchRightsVerified;
    private final String hedgeAccountScope;
    private final String cashFeasibilityAccountScope;
    private final String basisUnderlyingKey;

    @Autowired
    public FnoFuturesResearchService(ObjectProvider<UpstoxFnoV3LiveFeedService> feed,
            MarketSessionCalendar calendar, FnoFuturesContractRegistry registry,
            FnoClosingSnapshotStore store,
            FnoVerifiedFinalPriceOiSessionStore finalSessionStore,
            FnoCashFuturesFeasibilityStore cashFeasibilityStore,
            FnoVerifiedCompletedOhlcvOiStore completedOhlcvOiStore,
            FnoIndexHedgeResearchStore indexHedgeStore,
            FnoVerifiedIntradayLiquidityStore liquidityStore,
            FnoVerifiedExpiryBasisPairStore expiryBasisStore,
            @Value("${minttick.fno-research.research-rights-verified:false}") boolean researchRightsVerified,
            @Value("${minttick.fno-research.contract-discovery-verified:false}") boolean contractDiscoveryVerified,
            @Value("${minttick.fno-research.finalized-price-research-rights-verified:false}") boolean finalPriceResearchRightsVerified,
            @Value("${minttick.fno-research.cash-feasibility-research-rights-verified:false}") boolean cashFeasibilityResearchRightsVerified,
            @Value("${minttick.fno-research.completed-ohlcv-research-rights-verified:false}") boolean completedOhlcvResearchRightsVerified,
            @Value("${minttick.fno-research.hedge-research-rights-verified:false}") boolean hedgeResearchRightsVerified,
            @Value("${minttick.fno-research.liquidity-research-rights-verified:false}") boolean liquidityResearchRightsVerified,
            @Value("${minttick.fno-research.expiry-basis-research-rights-verified:false}") boolean expiryBasisResearchRightsVerified,
            @Value("${minttick.fno-research.hedge-account-scope:}") String hedgeAccountScope,
            @Value("${minttick.fno-research.cash-feasibility-account-scope:}") String cashFeasibilityAccountScope,
            @Value("${minttick.fno-research.basis-underlying-key:}") String basisUnderlyingKey) {
        this.feed = feed;
        this.calendar = calendar;
        this.registry = registry;
        this.store = store;
        this.finalSessionStore = finalSessionStore;
        this.cashFeasibilityStore = cashFeasibilityStore;
        this.completedOhlcvOiStore = completedOhlcvOiStore;
        this.indexHedgeStore = indexHedgeStore;
        this.liquidityStore = liquidityStore;
        this.expiryBasisStore = expiryBasisStore;
        this.researchRightsVerified = researchRightsVerified;
        this.contractDiscoveryVerified = contractDiscoveryVerified;
        this.finalPriceResearchRightsVerified = finalPriceResearchRightsVerified;
        this.cashFeasibilityResearchRightsVerified = cashFeasibilityResearchRightsVerified;
        this.completedOhlcvResearchRightsVerified = completedOhlcvResearchRightsVerified;
        this.hedgeResearchRightsVerified = hedgeResearchRightsVerified;
        this.liquidityResearchRightsVerified = liquidityResearchRightsVerified;
        this.expiryBasisResearchRightsVerified = expiryBasisResearchRightsVerified;
        this.hedgeAccountScope = hedgeAccountScope == null ? "" : hedgeAccountScope.trim();
        this.cashFeasibilityAccountScope =
            cashFeasibilityAccountScope == null ? "" : cashFeasibilityAccountScope.trim();
        this.basisUnderlyingKey = basisUnderlyingKey == null ? "" : basisUnderlyingKey.trim();
    }

    public FnoFuturesResearchService(ObjectProvider<UpstoxFnoV3LiveFeedService> feed,
            MarketSessionCalendar calendar, FnoFuturesContractRegistry registry,
            FnoClosingSnapshotStore store,
            FnoVerifiedFinalPriceOiSessionStore finalSessionStore,
            FnoCashFuturesFeasibilityStore cashFeasibilityStore,
            FnoVerifiedCompletedOhlcvOiStore completedOhlcvOiStore,
            FnoIndexHedgeResearchStore indexHedgeStore,
            FnoVerifiedIntradayLiquidityStore liquidityStore,
            @Value("${minttick.fno-research.research-rights-verified:false}") boolean researchRightsVerified,
            @Value("${minttick.fno-research.contract-discovery-verified:false}") boolean contractDiscoveryVerified,
            @Value("${minttick.fno-research.finalized-price-research-rights-verified:false}") boolean finalPriceResearchRightsVerified,
            @Value("${minttick.fno-research.cash-feasibility-research-rights-verified:false}") boolean cashFeasibilityResearchRightsVerified,
            @Value("${minttick.fno-research.completed-ohlcv-research-rights-verified:false}") boolean completedOhlcvResearchRightsVerified,
            @Value("${minttick.fno-research.hedge-research-rights-verified:false}") boolean hedgeResearchRightsVerified,
            @Value("${minttick.fno-research.liquidity-research-rights-verified:false}") boolean liquidityResearchRightsVerified,
            @Value("${minttick.fno-research.hedge-account-scope:}") String hedgeAccountScope,
            @Value("${minttick.fno-research.cash-feasibility-account-scope:}") String cashFeasibilityAccountScope,
            @Value("${minttick.fno-research.basis-underlying-key:}") String basisUnderlyingKey) {
        this(feed, calendar, registry, store, finalSessionStore,
            cashFeasibilityStore, completedOhlcvOiStore, indexHedgeStore,
            liquidityStore, null, researchRightsVerified,
            contractDiscoveryVerified, finalPriceResearchRightsVerified,
            cashFeasibilityResearchRightsVerified, completedOhlcvResearchRightsVerified,
            hedgeResearchRightsVerified, liquidityResearchRightsVerified,
            false, hedgeAccountScope, cashFeasibilityAccountScope, basisUnderlyingKey);
    }

    /** STR-178 source fixtures keep STR-179 research and intraday feed denied. */
    public FnoFuturesResearchService(ObjectProvider<UpstoxFnoV3LiveFeedService> feed,
            MarketSessionCalendar calendar, FnoFuturesContractRegistry registry,
            FnoClosingSnapshotStore store,
            FnoVerifiedFinalPriceOiSessionStore finalSessionStore,
            FnoCashFuturesFeasibilityStore cashFeasibilityStore,
            FnoVerifiedCompletedOhlcvOiStore completedOhlcvOiStore,
            FnoIndexHedgeResearchStore indexHedgeStore,
            boolean researchRightsVerified, boolean contractDiscoveryVerified,
            boolean finalPriceResearchRightsVerified,
            boolean cashFeasibilityResearchRightsVerified,
            boolean completedOhlcvResearchRightsVerified,
            boolean hedgeResearchRightsVerified, String hedgeAccountScope,
            String cashFeasibilityAccountScope, String basisUnderlyingKey) {
        this(feed, calendar, registry, store, finalSessionStore,
            cashFeasibilityStore, completedOhlcvOiStore, indexHedgeStore, null,
            researchRightsVerified, contractDiscoveryVerified,
            finalPriceResearchRightsVerified, cashFeasibilityResearchRightsVerified,
            completedOhlcvResearchRightsVerified, hedgeResearchRightsVerified,
            false, hedgeAccountScope, cashFeasibilityAccountScope, basisUnderlyingKey);
    }

    /** Earlier source-only fixtures keep STR-178 research denied by default. */
    public FnoFuturesResearchService(ObjectProvider<UpstoxFnoV3LiveFeedService> feed,
            MarketSessionCalendar calendar, FnoFuturesContractRegistry registry,
            FnoClosingSnapshotStore store,
            FnoVerifiedFinalPriceOiSessionStore finalSessionStore,
            FnoCashFuturesFeasibilityStore cashFeasibilityStore,
            FnoVerifiedCompletedOhlcvOiStore completedOhlcvOiStore,
            boolean researchRightsVerified, boolean contractDiscoveryVerified,
            boolean finalPriceResearchRightsVerified, boolean cashFeasibilityResearchRightsVerified,
            boolean completedOhlcvResearchRightsVerified,
            String cashFeasibilityAccountScope, String basisUnderlyingKey) {
        this(feed, calendar, registry, store, finalSessionStore,
            cashFeasibilityStore, completedOhlcvOiStore, null,
            researchRightsVerified, contractDiscoveryVerified,
            finalPriceResearchRightsVerified, cashFeasibilityResearchRightsVerified,
            completedOhlcvResearchRightsVerified, false, "",
            cashFeasibilityAccountScope, basisUnderlyingKey);
    }

    /** Source-only compatibility for existing STR-175 research tests. */
    public FnoFuturesResearchService(ObjectProvider<UpstoxFnoV3LiveFeedService> feed,
            MarketSessionCalendar calendar, FnoFuturesContractRegistry registry,
            FnoClosingSnapshotStore store,
            FnoVerifiedFinalPriceOiSessionStore finalSessionStore,
            FnoCashFuturesFeasibilityStore cashFeasibilityStore,
            boolean researchRightsVerified, boolean contractDiscoveryVerified,
            boolean finalPriceResearchRightsVerified, boolean cashFeasibilityResearchRightsVerified,
            String cashFeasibilityAccountScope, String basisUnderlyingKey) {
        this(feed, calendar, registry, store, finalSessionStore, cashFeasibilityStore,
            null, researchRightsVerified, contractDiscoveryVerified,
            finalPriceResearchRightsVerified, cashFeasibilityResearchRightsVerified,
            false, cashFeasibilityAccountScope, basisUnderlyingKey);
    }

    /** Existing STR-174 evidence fixtures keep STR-175 and STR-176 denied. */
    public FnoFuturesResearchService(ObjectProvider<UpstoxFnoV3LiveFeedService> feed,
            MarketSessionCalendar calendar, FnoFuturesContractRegistry registry,
            FnoClosingSnapshotStore store, FnoVerifiedFinalPriceOiSessionStore finalSessionStore,
            boolean researchRightsVerified, boolean contractDiscoveryVerified,
            boolean finalPriceResearchRightsVerified, String basisUnderlyingKey) {
        this(feed, calendar, registry, store, finalSessionStore, null, null,
            researchRightsVerified, contractDiscoveryVerified, finalPriceResearchRightsVerified,
            false, false, "", basisUnderlyingKey);
    }

    /** Legacy rights-denied research fixtures. */
    public FnoFuturesResearchService(ObjectProvider<UpstoxFnoV3LiveFeedService> feed,
            MarketSessionCalendar calendar, FnoFuturesContractRegistry registry,
            FnoClosingSnapshotStore store, boolean researchRightsVerified,
            boolean contractDiscoveryVerified, String basisUnderlyingKey) {
        this(feed, calendar, registry, store, null, null, null,
            researchRightsVerified, contractDiscoveryVerified, false, false, false,
            "", basisUnderlyingKey);
    }

    /** Current exchange-timed live quote from the shared V3 feed, or empty. */
    Optional<UpstoxFnoV3LiveFeedService.PaperQuote> liveQuote(String instrumentKey) {
        UpstoxFnoV3LiveFeedService live = feed.getIfAvailable();
        if (live == null || instrumentKey == null) return Optional.empty();
        return instrumentKey.startsWith("NSE_EQ|")
                ? live.eligibleResearchSpotQuote(instrumentKey)
                : live.eligiblePaperQuote(instrumentKey);
    }

    public List<StrategyStatus> evaluateAll(Instant now) {
        LocalDate today = now.atZone(IST).toLocalDate();
        boolean sessionOpen = calendar.isInsideFnoSession(now);
        // One rights-gated V102 read per evaluation cycle for STR-176 and STR-177.
        var completedWindow = new CompletedOhlcvReadOnce();
        return List.of(
            evaluateBasisCarry(now, today, sessionOpen),
            evaluateCalendarSpread(now, today, sessionOpen),
            evaluateRollover(now, today),
            evaluateOpenInterestTrend(now, today),
            evaluatePriceOiDivergence(now, today),
            evaluateCashFuturesFeasibility(now, today, sessionOpen),
            evaluateFuturesMomentum(now, today, completedWindow),
            evaluateFuturesMeanReversion(now, today, completedWindow),
            evaluateIndexFuturesHedge(now, today, sessionOpen),
            evaluateContractLiquidityGate(now, today, sessionOpen),
            evaluateExpiryBasisReversion(now, today));
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


    /** Source-only STR-173 integration. Historical rights are independent of broker permissions. */
    private StrategyStatus evaluateOpenInterestTrend(Instant now, LocalDate today) {
        var rights = new FnoFuturesOpenInterestTrendV1.SourceRights(
            researchRightsVerified, contractDiscoveryVerified);
        if (!researchRightsVerified || !contractDiscoveryVerified) {
            return oiBlocked(now, rights, "OI_SOURCE_RIGHTS_NOT_VERIFIED", 0, 0);
        }
        var master = registry.status();
        if (master == null || !"LOADED".equals(master.state())
                || master.fetchedAt() == null || master.sha256() == null
                || master.sha256().isBlank() || master.fetchedAt().isAfter(now)
                || java.time.Duration.between(master.fetchedAt(), now).toHours() > 48) {
            return oiBlocked(now, rights, "OI_INSTRUMENT_MASTER_NOT_CURRENT", 0, 0);
        }
        var pair = registry.nearFar(NIFTY, today);
        if (pair.isEmpty()) {
            return oiBlocked(now, rights, "OI_VERIFIED_FUTURES_CONTRACT_MISSING", 0, 0);
        }
        FuturesContract chosen = pair.get().near().expiry().isBefore(today.plusDays(7))
            ? pair.get().far() : pair.get().near();
        var contract = new FnoFuturesOpenInterestTrendV1.Contract(
            chosen.instrumentKey(), chosen.underlyingKey(), chosen.expiry(),
            chosen.lotSize(), true);
        final List<FnoClosingSnapshotStore.StoredRow> rows;
        try {
            rows = store.verifiedOiContractRows(chosen.instrumentKey(),
                today.minusDays(OI_LOOKBACK_DAYS), today);
        } catch (RuntimeException sourceUnavailable) {
            return oiBlocked(now, rights, "OI_HISTORY_READ_UNAVAILABLE", 1, 0);
        }
        if (rows == null || rows.size() < 5) {
            return oiBlocked(now, rights, "OI_FIVE_COMPLETED_SESSIONS_MISSING",
                1, rows == null ? 0 : rows.size());
        }
        String provider = null;
        var dates = new java.util.HashSet<LocalDate>();
        for (var row : rows) {
            if (row == null || row.sessionDate() == null
                    || !dates.add(row.sessionDate())
                    || !chosen.instrumentKey().equals(row.instrumentKey())
                    || !FnoMarketDataProvider.isSessionHistorySource(row.providerLabel())
                    || (provider != null && !provider.equals(row.providerLabel()))
                    || row.openInterestUnits() == null || row.sessionVolumeUnits() == null
                    || row.exchangeAt() == null || row.observedAt() == null) {
                return oiBlocked(now, rights, "OI_SOURCE_ROWS_AMBIGUOUS_OR_INCOMPLETE",
                    1, rows.size());
            }
            provider = row.providerLabel();
        }
        List<FnoClosingSnapshotStore.StoredRow> last = rows.subList(rows.size() - 5, rows.size());
        List<FnoFuturesOpenInterestTrendV1.CompletedSession> sessions = new ArrayList<>();
        LocalDate preceding = null;
        for (var row : last) {
            boolean complete = calendar.isTradingDay(row.sessionDate());
            if (preceding != null) {
                for (LocalDate date = preceding.plusDays(1);
                        date.isBefore(row.sessionDate()); date = date.plusDays(1)) {
                    if (calendar.isTradingDay(date)) complete = false;
                }
            }
            sessions.add(new FnoFuturesOpenInterestTrendV1.CompletedSession(
                row.sessionDate(), complete, row.instrumentKey(), row.providerLabel(),
                row.exchangeAt(), row.observedAt(),
                row.openInterestUnits(), row.sessionVolumeUnits()));
            preceding = row.sessionDate();
        }
        var result = FnoFuturesOpenInterestTrendV1.evaluate(
            contract, List.copyOf(sessions), rights, OI_THRESHOLDS, now);
        return status(result.strategyId(), result.status().name(), result.reasonCode(),
            now, provider, 1, 0, sessions.size(), "OI_RESEARCH_ONLY_NO_ORDER_PERMISSION");
    }

    /** Fail-closed input result still invokes the distinct evaluator. */
    private static StrategyStatus oiBlocked(Instant now,
            FnoFuturesOpenInterestTrendV1.SourceRights rights,
            String reason, int contractCount, int rowCount) {
        var result = FnoFuturesOpenInterestTrendV1.evaluate(
            null, List.of(), rights, OI_THRESHOLDS, now);
        return status(result.strategyId(), result.status().name(), reason,
            now, "NONE", contractCount, 0, rowCount, "OI_INPUTS_BLOCKED");
    }


    /**
     * STR-174 requires independently attested FINAL futures closing prices, NOT
     * V99 bid/ask snapshots or LTP. Source receipt and original first-known times
     * are checked in the separate read-only store and again by the evaluator.
     */
    private StrategyStatus evaluatePriceOiDivergence(Instant now, LocalDate today) {
        var rights = new FnoFuturesPriceOiDivergenceV1.SourceRights(
            researchRightsVerified && finalPriceResearchRightsVerified,
            researchRightsVerified, contractDiscoveryVerified);
        if (!researchRightsVerified || !contractDiscoveryVerified
                || !finalPriceResearchRightsVerified) {
            return priceOiBlocked(now, rights, "PRICE_OI_SOURCE_RIGHTS_NOT_VERIFIED", 0, 0);
        }
        if (finalSessionStore == null) {
            return priceOiBlocked(now, rights, "PRICE_OI_FINAL_SOURCE_ADAPTER_MISSING", 0, 0);
        }
        var master = registry.status();
        if (master == null || !"LOADED".equals(master.state())
                || master.fetchedAt() == null || master.sha256() == null
                || master.sha256().isBlank() || master.fetchedAt().isAfter(now)
                || java.time.Duration.between(master.fetchedAt(), now).toHours() > 48) {
            return priceOiBlocked(now, rights, "PRICE_OI_INSTRUMENT_MASTER_NOT_CURRENT", 0, 0);
        }
        var pair = registry.nearFar(NIFTY, today);
        if (pair.isEmpty()) {
            return priceOiBlocked(now, rights, "PRICE_OI_FUTURES_CONTRACT_NOT_RESOLVED", 0, 0);
        }
        FuturesContract chosen = pair.get().near().expiry().isBefore(today.plusDays(7))
            ? pair.get().far() : pair.get().near();
        var contract = new FnoFuturesPriceOiDivergenceV1.Contract(
            chosen.instrumentKey(), chosen.underlyingKey(), chosen.expiry(),
            chosen.lotSize(), true);
        final List<FnoVerifiedFinalPriceOiSessionStore.FinalSession> rows;
        try {
            rows = finalSessionStore.readExactContract(chosen.instrumentKey(),
                today.minusDays(OI_LOOKBACK_DAYS), today, now);
        } catch (RuntimeException inaccessible) {
            // Do not disclose SQL, broker tokens or evidence identifiers.
            return priceOiBlocked(now, rights, "PRICE_OI_FINAL_SESSION_READ_UNAVAILABLE", 1, 0);
        }
        if (rows == null || rows.size() < 5) {
            return priceOiBlocked(now, rights, "PRICE_OI_FIVE_VERIFIED_FINAL_SESSIONS_MISSING",
                1, rows == null ? 0 : rows.size());
        }
        String provider = null;
        LocalDate preceding = null;
        for (var row : rows) {
            if (row == null || row.sessionDate() == null
                    || !chosen.instrumentKey().equals(row.instrumentKey())
                    || !chosen.underlyingKey().equals(row.underlyingKey())
                    || !chosen.expiry().equals(row.expiry())
                    || chosen.lotSize() != row.lotSize()
                    || !FnoMarketDataProvider.isSessionHistorySource(row.providerLabel())
                    || (provider != null && !provider.equals(row.providerLabel()))
                    || (preceding != null && !preceding.isBefore(row.sessionDate()))
                    || row.finalClosePrice() == null
                    || row.finalPriceFirstAvailableAt() == null
                    || row.finalizedOiFirstAvailableAt() == null
                    || row.verifiedExchangeCloseAt() == null
                    || row.sessionDate().isAfter(today)
                    || row.sessionDate().isBefore(today.minusDays(OI_LOOKBACK_DAYS))
                    || !row.verifiedExchangeCloseAt().equals(
                        officialNonExpiringFuturesClose(row.sessionDate()))) {
                return priceOiBlocked(now, rights, "PRICE_OI_FINAL_ROWS_INCOMPLETE_OR_AMBIGUOUS",
                    1, rows.size());
            }
            provider = row.providerLabel();
            preceding = row.sessionDate();
        }
        List<FnoVerifiedFinalPriceOiSessionStore.FinalSession> last =
            rows.subList(rows.size() - 5, rows.size());
        List<FnoFuturesPriceOiDivergenceV1.CompletedSession> sessions = new ArrayList<>();
        LocalDate prev = null;
        for (var row : last) {
            boolean complete = calendar.isTradingDay(row.sessionDate());
            if (prev != null) {
                for (LocalDate d = prev.plusDays(1); d.isBefore(row.sessionDate()); d = d.plusDays(1)) {
                    if (calendar.isTradingDay(d)) complete = false;
                }
            }
            sessions.add(new FnoFuturesPriceOiDivergenceV1.CompletedSession(
                row.sessionDate(), complete, row.instrumentKey(), row.providerLabel(),
                row.finalClosePrice(), true, row.finalPriceFirstAvailableAt(),
                row.finalOpenInterestUnits(), row.tradedVolumeUnits(),
                row.finalizedOiFirstAvailableAt()));
            prev = row.sessionDate();
        }
        var result = FnoFuturesPriceOiDivergenceV1.evaluate(
            contract, List.copyOf(sessions), rights, PRICE_OI_THRESHOLDS, now);
        return status(result.strategyId(), result.status().name(), result.reasonCode(),
            now, provider, 1, 0, sessions.size(), "PRICE_OI_FINAL_RECEIPT_RESEARCH_ONLY");
    }

    private static Instant officialNonExpiringFuturesClose(LocalDate date) {
        java.time.LocalTime close = date.isBefore(LocalDate.of(2026, 8, 3))
            ? java.time.LocalTime.of(15, 30)
            : date.isBefore(LocalDate.of(2026, 12, 7))
            ? java.time.LocalTime.of(15, 40) : java.time.LocalTime.of(15, 45);
        return date.atTime(close).atZone(IST).toInstant();
    }

    private static StrategyStatus priceOiBlocked(Instant now,
            FnoFuturesPriceOiDivergenceV1.SourceRights rights,
            String reason, int contracts, int sourceRows) {
        // Always execute the distinct evaluator, including fail-closed paths.
        var blocked = FnoFuturesPriceOiDivergenceV1.evaluate(
            null, List.of(), rights, PRICE_OI_THRESHOLDS, now);
        return status(blocked.strategyId(), blocked.status().name(), reason,
            now, "NONE", contracts, 0, sourceRows, "PRICE_OI_FINAL_SOURCE_BLOCKED");
    }


    /**
     * STR-175: one-lot cash-equity BUY + same-stock futures SELL feasibility.
     * The existing shared V3 feed supplies only exchange-timed bid/ask. Verified
     * exchange L1 depth, account funds, delivery/margin and distinct fee receipts
     * must originate separately from V101; never infer from LTP or balances.
     */
    private StrategyStatus evaluateCashFuturesFeasibility(Instant now,
            LocalDate today, boolean sessionOpen) {
        var rights = new FnoFuturesBasisCarryV1.SourceRights(
            researchRightsVerified && cashFeasibilityResearchRightsVerified,
            researchRightsVerified && cashFeasibilityResearchRightsVerified,
            sessionOpen, false);
        if (!researchRightsVerified || !contractDiscoveryVerified
                || !cashFeasibilityResearchRightsVerified) {
            return cashFeasibilityBlocked(now, rights,
                "CASH_FUT_SOURCE_RIGHTS_NOT_VERIFIED", 0, 0);
        }
        if (!sessionOpen) {
            return cashFeasibilityBlocked(now, rights, "CASH_FUT_EXCHANGE_SESSION_CLOSED", 0, 0);
        }
        if (cashFeasibilityStore == null
                || !cashFeasibilityAccountScope.matches("[A-Za-z0-9_-]{8,80}")
                || !basisUnderlyingKey.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")) {
            return cashFeasibilityBlocked(now, rights,
                "CASH_FUT_ACCOUNT_OR_STOCK_SCOPE_NOT_CONFIGURED", 0, 0);
        }

        var master = registry.status();
        if (master == null || !"LOADED".equals(master.state())
                || master.fetchedAt() == null || master.sha256() == null
                || master.sha256().isBlank() || master.fetchedAt().isAfter(now)
                || java.time.Duration.between(master.fetchedAt(), now).toHours() > 48) {
            return cashFeasibilityBlocked(now, rights,
                "CASH_FUT_INSTRUMENT_MASTER_NOT_CURRENT", 0, 0);
        }
        Optional<NearFar> pair = registry.nearFar(basisUnderlyingKey, today);
        if (pair.isEmpty() || !basisUnderlyingKey.equals(pair.get().near().underlyingKey())) {
            return cashFeasibilityBlocked(now, rights,
                "CASH_FUT_SINGLE_STOCK_CONTRACT_NOT_RESOLVED", 0, 0);
        }
        FuturesContract future = pair.get().near();
        var spotQuote = liveQuote(basisUnderlyingKey);
        var futureQuote = liveQuote(future.instrumentKey());
        int fresh = (spotQuote.isPresent() ? 1 : 0) + (futureQuote.isPresent() ? 1 : 0);
        if (fresh != 2) {
            return cashFeasibilityBlocked(now, rights,
                "CASH_FUT_MATCHED_LIVE_QUOTES_MISSING", 1, fresh);
        }
        final Optional<FnoCashFuturesFeasibilityStore.Proof> receipt;
        try {
            receipt = cashFeasibilityStore.currentSingleProof(cashFeasibilityAccountScope,
                basisUnderlyingKey, future.instrumentKey(), future.expiry(), future.lotSize(), now);
        } catch (RuntimeException unavailable) {
            return cashFeasibilityBlocked(now, rights,
                "CASH_FUT_FEASIBILITY_EVIDENCE_READ_UNAVAILABLE", 1, fresh);
        }
        if (receipt == null || receipt.isEmpty()) {
            return cashFeasibilityBlocked(now, rights,
                "CASH_FUT_DEPTH_MARGIN_DELIVERY_COST_EVIDENCE_MISSING", 1, fresh);
        }
        var proof = receipt.get();
        var spot = spotQuote.get();
        var fut = futureQuote.get();
        boolean sameEvidence = "UPSTOX_V3".equals(proof.provider())
            && cashFeasibilityAccountScope.equals(proof.accountScope())
            && basisUnderlyingKey.equals(proof.spotKey())
            && future.instrumentKey().equals(proof.futureKey())
            && future.expiry().equals(proof.expiry())
            && future.lotSize() == proof.lotSize()
            && proof.spotAskAtDepth() != null
            && proof.futuresBidAtDepth() != null
            && proof.spotAskAtDepth().compareTo(spot.ask()) == 0
            && proof.futuresBidAtDepth().compareTo(fut.bid()) == 0
            && proof.depthExchangeAt() != null
            && proof.depthFirstAvailableAt() != null
            && !proof.depthFirstAvailableAt().isAfter(now)
            && !proof.depthExchangeAt().isAfter(now)
            && java.time.Duration.between(proof.depthExchangeAt(), now).compareTo(
                java.time.Duration.ofSeconds(15)) <= 0
            && java.time.Duration.between(proof.depthExchangeAt(), spot.exchangeAt())
                .abs().compareTo(java.time.Duration.ofSeconds(10)) <= 0
            && java.time.Duration.between(proof.depthExchangeAt(), fut.exchangeAt())
                .abs().compareTo(java.time.Duration.ofSeconds(10)) <= 0;
        if (!sameEvidence) {
            return cashFeasibilityBlocked(now, rights,
                "CASH_FUT_PRICE_DEPTH_OR_ACCOUNT_PROOF_MISMATCH", 1, fresh);
        }
        var contract = new FnoFuturesBasisCarryV1.Contract(
            basisUnderlyingKey, future.instrumentKey(), future.underlyingKey(),
            future.expiry(), future.lotSize(), true, true);
        var buyStock = new FnoCashFuturesArbitrageResearchV1.Leg(
            spot.instrumentKey(), "UPSTOX_V3",
            new FnoFuturesBasisCarryV1.Quote(
                spot.bid(), spot.ask(), spot.exchangeAt(), spot.observedAt()),
            proof.spotAskDepthUnits(), true);
        var sellFuture = new FnoCashFuturesArbitrageResearchV1.Leg(
            fut.instrumentKey(), "UPSTOX_V3",
            new FnoFuturesBasisCarryV1.Quote(
                fut.bid(), fut.ask(), fut.exchangeAt(), fut.observedAt()),
            proof.futuresBidDepthUnits(), true);
        var result = FnoCashFuturesArbitrageResearchV1.evaluate(
            contract, buyStock, sellFuture, rights, proof.basis(),
            proof.capital(), proof.costs(), now);
        return status(result.strategyId(), result.status().name(),
            result.reasonCode(), now, "UPSTOX_V3", 1, fresh, 0,
            "CASH_FUT_VERIFIED_FEASIBILITY_RECEIPT_RESEARCH_ONLY");
    }

    private static StrategyStatus cashFeasibilityBlocked(Instant now,
            FnoFuturesBasisCarryV1.SourceRights rights, String reason,
            int contracts, int quotes) {
        // The distinct evaluator is invoked even for blocked as-of source paths.
        var result = FnoCashFuturesArbitrageResearchV1.evaluate(
            null, null, null, rights, null, null, null, now);
        return status(result.strategyId(), result.status().name(),
            reason, now, "NONE", contracts, quotes, 0,
            "CASH_FUT_FEASIBILITY_SOURCE_BLOCKED");
    }



    /** Per-request fan-out: avoid redundant SQL and inconsistent as-of snapshots. */
    private static final class CompletedOhlcvReadOnce {
        private boolean attempted;
        private boolean failed;
        private String key;
        private LocalDate from;
        private LocalDate to;
        private Instant asOf;
        private List<FnoVerifiedCompletedOhlcvOiStore.VerifiedBar> rows;

        List<FnoVerifiedCompletedOhlcvOiStore.VerifiedBar> read(
                FnoVerifiedCompletedOhlcvOiStore source, String instrumentKey,
                LocalDate start, LocalDate end, Instant instant) {
            if (!attempted) {
                attempted = true;
                key = instrumentKey;
                from = start;
                to = end;
                asOf = instant;
                try {
                    rows = source.readExactContract(instrumentKey, start, end, instant);
                } catch (RuntimeException unavailable) {
                    failed = true;
                    throw unavailable;
                }
            }
            if (!key.equals(instrumentKey) || !from.equals(start) || !to.equals(end)
                    || !asOf.equals(instant) || failed) {
                // Never reuse a result across a different instrument or evaluation.
                throw new IllegalStateException("FNO_COMPLETED_SOURCE_WINDOW_UNAVAILABLE");
            }
            return rows;
        }
    }

    /** STR-176: verified original-time futures OHLCV and separately finalized OI. */
    private StrategyStatus evaluateFuturesMomentum(Instant now, LocalDate today,
            CompletedOhlcvReadOnce completedWindow) {
        var rights = new FnoFuturesMomentumV1.SourceRights(
            researchRightsVerified && completedOhlcvResearchRightsVerified,
            researchRightsVerified && completedOhlcvResearchRightsVerified,
            contractDiscoveryVerified);
        if (!researchRightsVerified || !contractDiscoveryVerified
                || !completedOhlcvResearchRightsVerified) {
            return momentumBlocked(now, rights, "FUT_MOM_SOURCE_RIGHTS_NOT_VERIFIED", 0, 0);
        }
        if (completedOhlcvOiStore == null) {
            return momentumBlocked(now, rights, "FUT_MOM_COMPLETED_SOURCE_ADAPTER_MISSING", 0, 0);
        }
        var master = registry.status();
        if (master == null || !"LOADED".equals(master.state())
                || master.fetchedAt() == null || master.sha256() == null
                || master.sha256().isBlank() || master.fetchedAt().isAfter(now)
                || java.time.Duration.between(master.fetchedAt(), now).toHours() > 48) {
            return momentumBlocked(now, rights, "FUT_MOM_INSTRUMENT_MASTER_NOT_CURRENT", 0, 0);
        }
        var pair = registry.nearFar(NIFTY, today);
        if (pair.isEmpty()) {
            return momentumBlocked(now, rights, "FUT_MOM_FUTURES_CONTRACT_NOT_RESOLVED", 0, 0);
        }
        FuturesContract chosen = pair.get().near().expiry().isBefore(today.plusDays(7))
            ? pair.get().far() : pair.get().near();
        final List<FnoVerifiedCompletedOhlcvOiStore.VerifiedBar> rows;
        try {
            rows = completedWindow.read(completedOhlcvOiStore,
                chosen.instrumentKey(), today.minusDays(OI_LOOKBACK_DAYS), today, now);
        } catch (RuntimeException unavailable) {
            return momentumBlocked(now, rights, "FUT_MOM_SOURCE_READ_UNAVAILABLE", 1, 0);
        }
        if (rows == null || rows.size() < 7) {
            return momentumBlocked(now, rights, "FUT_MOM_SEVEN_VERIFIED_SESSIONS_MISSING",
                1, rows == null ? 0 : rows.size());
        }
        String provider = null;
        LocalDate listedFrom = null;
        LocalDate prev = null;
        for (var row : rows) {
            if (row == null || row.sessionDate() == null || row.listedFrom() == null
                    || !chosen.instrumentKey().equals(row.instrumentKey())
                    || !chosen.underlyingKey().equals(row.underlyingKey())
                    || !chosen.expiry().equals(row.expiry())
                    || chosen.lotSize() != row.lotSize()
                    || !FnoMarketDataProvider.isSessionHistorySource(row.provider())
                    || (provider != null && !provider.equals(row.provider()))
                    || (listedFrom != null && !listedFrom.equals(row.listedFrom()))
                    || (prev != null && !prev.isBefore(row.sessionDate()))
                    || row.sessionDate().isAfter(today)
                    || row.sessionDate().isBefore(today.minusDays(OI_LOOKBACK_DAYS))
                    || row.verifiedSessionCloseAt() == null
                    || !officialNonExpiringFuturesClose(row.sessionDate()).equals(
                        row.verifiedSessionCloseAt())
                    || row.ohlcvFirstAvailableAt() == null || row.oiFirstAvailableAt() == null) {
                return momentumBlocked(now, rights, "FUT_MOM_SOURCE_ROWS_INCOMPLETE_OR_AMBIGUOUS",
                    1, rows.size());
            }
            provider = row.provider();
            listedFrom = row.listedFrom();
            prev = row.sessionDate();
        }
        var last = rows.subList(rows.size() - 7, rows.size());
        List<FnoFuturesMomentumV1.CompletedSession> bars = new ArrayList<>();
        prev = null;
        for (var row : last) {
            boolean complete = calendar.isTradingDay(row.sessionDate());
            if (prev != null) {
                for (LocalDate d = prev.plusDays(1); d.isBefore(row.sessionDate());
                        d = d.plusDays(1)) {
                    if (calendar.isTradingDay(d)) complete = false;
                }
            }
            bars.add(new FnoFuturesMomentumV1.CompletedSession(
                row.sessionDate(), complete, row.instrumentKey(), row.provider(),
                row.open(), row.high(), row.low(), row.close(), true,
                row.ohlcvFirstAvailableAt(), row.tradedVolumeUnits(),
                row.openInterestUnits(), row.oiFirstAvailableAt()));
            prev = row.sessionDate();
        }
        var contract = new FnoFuturesMomentumV1.Contract(
            chosen.instrumentKey(), chosen.underlyingKey(), listedFrom,
            chosen.expiry(), chosen.lotSize(), true);
        var result = FnoFuturesMomentumV1.evaluate(
            contract, List.copyOf(bars), rights, MOMENTUM_THRESHOLDS, now);
        return status(result.strategyId(), result.status().name(), result.reasonCode(),
            now, provider, 1, 0, bars.size(), "COMPLETED_OHLCV_OI_RESEARCH_NO_ORDER");
    }

    private static StrategyStatus momentumBlocked(Instant now,
            FnoFuturesMomentumV1.SourceRights rights, String reason,
            int contracts, int history) {
        // Distinct evaluator observed even when every source datum fails closed.
        var blocked = FnoFuturesMomentumV1.evaluate(
            null, List.of(), rights, MOMENTUM_THRESHOLDS, now);
        return status(blocked.strategyId(), blocked.status().name(), reason,
            now, "NONE", contracts, 0, history, "FUT_MOM_SOURCE_BLOCKED");
    }


    /** STR-177: ten source-finalized OHLCV/OI sessions; never paper or live. */
    private StrategyStatus evaluateFuturesMeanReversion(Instant now, LocalDate today,
            CompletedOhlcvReadOnce completedWindow) {
        var rights = new FnoFuturesMeanReversionV1.SourceRights(
            researchRightsVerified && completedOhlcvResearchRightsVerified,
            researchRightsVerified && completedOhlcvResearchRightsVerified,
            contractDiscoveryVerified);
        if (!researchRightsVerified || !contractDiscoveryVerified
                || !completedOhlcvResearchRightsVerified) {
            return meanReversionBlocked(now, rights, "FUT_MR_SOURCE_RIGHTS_NOT_VERIFIED", 0, 0);
        }
        if (completedOhlcvOiStore == null) {
            return meanReversionBlocked(now, rights, "FUT_MR_COMPLETED_SOURCE_ADAPTER_MISSING",
                0, 0);
        }
        var master = registry.status();
        if (master == null || !"LOADED".equals(master.state())
                || master.fetchedAt() == null || master.sha256() == null
                || master.sha256().isBlank() || master.fetchedAt().isAfter(now)
                || java.time.Duration.between(master.fetchedAt(), now).toHours() > 48) {
            return meanReversionBlocked(now, rights, "FUT_MR_INSTRUMENT_MASTER_NOT_CURRENT",
                0, 0);
        }
        var pair = registry.nearFar(NIFTY, today);
        if (pair.isEmpty()) {
            return meanReversionBlocked(now, rights, "FUT_MR_FUTURES_CONTRACT_NOT_RESOLVED",
                0, 0);
        }
        FuturesContract chosen = pair.get().near().expiry().isBefore(today.plusDays(7))
            ? pair.get().far() : pair.get().near();
        final List<FnoVerifiedCompletedOhlcvOiStore.VerifiedBar> rows;
        try {
            rows = completedWindow.read(completedOhlcvOiStore,
                chosen.instrumentKey(), today.minusDays(OI_LOOKBACK_DAYS), today, now);
        } catch (RuntimeException unavailable) {
            return meanReversionBlocked(now, rights, "FUT_MR_SOURCE_READ_UNAVAILABLE", 1, 0);
        }
        if (rows == null || rows.size() < 10) {
            return meanReversionBlocked(now, rights, "FUT_MR_TEN_VERIFIED_SESSIONS_MISSING",
                1, rows == null ? 0 : rows.size());
        }
        String provider = null;
        LocalDate listedFrom = null;
        LocalDate prev = null;
        for (var row : rows) {
            if (row == null || row.sessionDate() == null || row.listedFrom() == null
                    || !chosen.instrumentKey().equals(row.instrumentKey())
                    || !chosen.underlyingKey().equals(row.underlyingKey())
                    || !chosen.expiry().equals(row.expiry())
                    || chosen.lotSize() != row.lotSize()
                    || !FnoMarketDataProvider.isSessionHistorySource(row.provider())
                    || (provider != null && !provider.equals(row.provider()))
                    || (listedFrom != null && !listedFrom.equals(row.listedFrom()))
                    || (prev != null && !prev.isBefore(row.sessionDate()))
                    || row.sessionDate().isAfter(today)
                    || row.sessionDate().isBefore(today.minusDays(OI_LOOKBACK_DAYS))
                    || row.verifiedSessionCloseAt() == null
                    || !officialNonExpiringFuturesClose(row.sessionDate()).equals(
                        row.verifiedSessionCloseAt())
                    || row.ohlcvFirstAvailableAt() == null || row.oiFirstAvailableAt() == null) {
                return meanReversionBlocked(now, rights,
                    "FUT_MR_SOURCE_ROWS_INCOMPLETE_OR_AMBIGUOUS", 1, rows.size());
            }
            provider = row.provider();
            listedFrom = row.listedFrom();
            prev = row.sessionDate();
        }
        var last = rows.subList(rows.size() - 10, rows.size());
        List<FnoFuturesMeanReversionV1.CompletedSession> bars = new ArrayList<>();
        prev = null;
        for (var row : last) {
            boolean complete = calendar.isTradingDay(row.sessionDate());
            if (prev != null) {
                for (LocalDate d = prev.plusDays(1); d.isBefore(row.sessionDate());
                        d = d.plusDays(1)) {
                    if (calendar.isTradingDay(d)) complete = false;
                }
            }
            bars.add(new FnoFuturesMeanReversionV1.CompletedSession(
                row.sessionDate(), complete, row.instrumentKey(), row.provider(),
                row.open(), row.high(), row.low(), row.close(), true,
                row.ohlcvFirstAvailableAt(), row.tradedVolumeUnits(),
                row.openInterestUnits(), row.oiFirstAvailableAt()));
            prev = row.sessionDate();
        }
        var contract = new FnoFuturesMeanReversionV1.Contract(
            chosen.instrumentKey(), chosen.underlyingKey(), listedFrom,
            chosen.expiry(), chosen.lotSize(), true);
        var decision = FnoFuturesMeanReversionV1.evaluate(
            contract, List.copyOf(bars), rights, MEAN_REVERSION_THRESHOLDS, now);
        return status(decision.strategyId(), decision.status().name(),
            decision.reasonCode(), now, provider, 1, 0, bars.size(),
            "COMPLETED_OHLCV_OI_MEAN_REVERSION_RESEARCH_NO_ORDER");
    }

    private static StrategyStatus meanReversionBlocked(Instant now,
            FnoFuturesMeanReversionV1.SourceRights rights,
            String reason, int contracts, int history) {
        var decision = FnoFuturesMeanReversionV1.evaluate(
            null, List.of(), rights, MEAN_REVERSION_THRESHOLDS, now);
        return status(decision.strategyId(), decision.status().name(), reason,
            now, "NONE", contracts, 0, history, "FUT_MR_SOURCE_BLOCKED");
    }


    /**
     * STR-178: read-only portfolio hedge feasibility using independently
     * attested beta, existing positions, margin, risk and L1 depth evidence.
     * Research rights are not order permissions; no hedge is submitted.
     */
    private StrategyStatus evaluateIndexFuturesHedge(Instant now,
            LocalDate today, boolean sessionOpen) {
        var rights = new FnoFuturesHedgeOverlayV1.SourceRights(
            researchRightsVerified && hedgeResearchRightsVerified,
            contractDiscoveryVerified,
            hedgeResearchRightsVerified);
        if (!researchRightsVerified || !contractDiscoveryVerified
                || !hedgeResearchRightsVerified) {
            return hedgeBlocked(now, rights, "HEDGE_RESEARCH_RIGHTS_NOT_VERIFIED", 0, 0);
        }
        if (!sessionOpen) {
            return hedgeBlocked(now, rights, "HEDGE_FUTURES_SESSION_CLOSED", 0, 0);
        }
        if (indexHedgeStore == null
                || !hedgeAccountScope.matches("[A-Za-z0-9_-]{8,80}")) {
            return hedgeBlocked(now, rights, "HEDGE_ACCOUNT_OR_SOURCE_NOT_CONFIGURED", 0, 0);
        }
        var master = registry.status();
        if (master == null || !"LOADED".equals(master.state())
                || master.fetchedAt() == null || master.sha256() == null
                || master.sha256().isBlank() || master.fetchedAt().isAfter(now)
                || java.time.Duration.between(master.fetchedAt(), now).toHours() > 48) {
            return hedgeBlocked(now, rights, "HEDGE_INSTRUMENT_MASTER_NOT_CURRENT", 0, 0);
        }
        var pair = registry.nearFar(NIFTY, today);
        if (pair.isEmpty() || !NIFTY.equals(pair.get().near().underlyingKey())) {
            return hedgeBlocked(now, rights, "HEDGE_VERIFIED_INDEX_FUTURE_NOT_RESOLVED", 0, 0);
        }
        FuturesContract chosen = pair.get().near().expiry().isBefore(today.plusDays(3))
            ? pair.get().far() : pair.get().near();
        var observed = liveQuote(chosen.instrumentKey());
        if (observed.isEmpty()) {
            return hedgeBlocked(now, rights, "HEDGE_LIVE_INDEX_FUTURE_QUOTE_MISSING", 1, 0);
        }
        final Optional<FnoIndexHedgeResearchStore.Proof> receipt;
        try {
            receipt = indexHedgeStore.oneCurrentProof(hedgeAccountScope,
                NIFTY, chosen.instrumentKey(), chosen.expiry(), chosen.lotSize(), now);
        } catch (RuntimeException failure) {
            return hedgeBlocked(now, rights, "HEDGE_PORTFOLIO_EVIDENCE_READ_UNAVAILABLE", 1, 1);
        }
        if (receipt == null || receipt.isEmpty()) {
            return hedgeBlocked(now, rights, "HEDGE_BETA_MARGIN_DEPTH_RISK_PROOF_MISSING", 1, 1);
        }
        var proof = receipt.get();
        var quote = observed.get();
        var attested = proof.market();
        if (attested == null || proof.portfolio() == null
                || proof.funding() == null || proof.stress() == null
                || !"UPSTOX_V3".equals(proof.provider())
                || !hedgeAccountScope.equals(proof.accountScope())
                || !NIFTY.equals(proof.indexKey())
                || !chosen.instrumentKey().equals(proof.futureKey())
                || !chosen.expiry().equals(proof.expiry())
                || chosen.lotSize() != proof.lotSize()
                || !NIFTY.equals(proof.portfolio().indexUnderlyingKey())
                || !chosen.instrumentKey().equals(attested.instrumentKey())
                || !"UPSTOX_V3".equals(attested.provider())
                || attested.bid() == null || attested.ask() == null
                || attested.bid().compareTo(quote.bid()) != 0
                || attested.ask().compareTo(quote.ask()) != 0
                || attested.exchangeAt() == null || quote.exchangeAt() == null
                || attested.observedAt() == null
                || attested.observedAt().isAfter(now)
                || attested.exchangeAt().isAfter(now)
                || java.time.Duration.between(attested.exchangeAt(), quote.exchangeAt())
                    .abs().compareTo(java.time.Duration.ofSeconds(10)) > 0) {
            return hedgeBlocked(now, rights, "HEDGE_QUOTE_ACCOUNT_OR_RISK_PROOF_MISMATCH", 1, 1);
        }
        var contract = new FnoFuturesHedgeOverlayV1.Contract(
            chosen.instrumentKey(), NIFTY, chosen.expiry(), chosen.lotSize(), true);
        var decision = FnoFuturesHedgeOverlayV1.evaluate(
            contract, proof.portfolio(), attested, rights,
            proof.funding(), proof.stress(), now);
        return status(decision.strategyId(), decision.status().name(),
            decision.reasonCode(), now, "UPSTOX_V3", 1, 1, 0,
            "RECONCILED_INDEX_HEDGE_RESEARCH_NO_ORDER");
    }

    private static StrategyStatus hedgeBlocked(Instant now,
            FnoFuturesHedgeOverlayV1.SourceRights rights,
            String reason, int contracts, int quotes) {
        var blocked = FnoFuturesHedgeOverlayV1.evaluate(
            null, null, null, rights, null, null, now);
        return status(blocked.strategyId(), blocked.status().name(),
            reason, now, "NONE", contracts, quotes, 0,
            "INDEX_HEDGE_SOURCE_BLOCKED");
    }


    /**
     * STR-179 is a reusable, research-only source liquidity gate. It is not
     * independent trading alpha or a broker risk-limit override.
     */
    private StrategyStatus evaluateContractLiquidityGate(Instant now, LocalDate today,
            boolean sessionOpen) {
        var rights = new FnoContractLiquidityGateV1.SourceRights(
            researchRightsVerified && liquidityResearchRightsVerified,
            researchRightsVerified && liquidityResearchRightsVerified,
            researchRightsVerified && liquidityResearchRightsVerified,
            contractDiscoveryVerified);
        if (!researchRightsVerified || !contractDiscoveryVerified
                || !liquidityResearchRightsVerified) {
            return liquidityBlocked(now, rights,
                "LIQUIDITY_SOURCE_RIGHTS_NOT_VERIFIED", 0, 0, 0);
        }
        if (!sessionOpen) {
            return liquidityBlocked(now, rights, "LIQUIDITY_FNO_SESSION_CLOSED", 0, 0, 0);
        }
        if (liquidityStore == null) {
            return liquidityBlocked(now, rights, "LIQUIDITY_SOURCE_ADAPTER_MISSING", 0, 0, 0);
        }
        var master = registry.status();
        if (master == null || !"LOADED".equals(master.state())
                || master.fetchedAt() == null || master.sha256() == null
                || master.sha256().isBlank() || master.fetchedAt().isAfter(now)
                || java.time.Duration.between(master.fetchedAt(), now).toHours() > 48) {
            return liquidityBlocked(now, rights, "LIQUIDITY_INSTRUMENT_MASTER_NOT_CURRENT",
                0, 0, 0);
        }
        var pair = registry.nearFar(NIFTY, today);
        if (pair.isEmpty() || !NIFTY.equals(pair.get().near().underlyingKey())) {
            return liquidityBlocked(now, rights, "LIQUIDITY_FUTURE_CONTRACT_NOT_RESOLVED",
                0, 0, 0);
        }
        FuturesContract chosen = pair.get().near();
        var current = liveQuote(chosen.instrumentKey());
        if (current.isEmpty()) {
            return liquidityBlocked(now, rights, "LIQUIDITY_CURRENT_LIVE_QUOTE_MISSING",
                1, 0, 0);
        }
        final List<FnoVerifiedIntradayLiquidityStore.Snapshot> sourceRows;
        try {
            sourceRows = liquidityStore.latestThreeFutures(
                chosen.instrumentKey(), chosen.expiry(), chosen.lotSize(), now);
        } catch (RuntimeException inaccessible) {
            return liquidityBlocked(now, rights, "LIQUIDITY_SOURCE_READ_UNAVAILABLE",
                1, 1, 0);
        }
        if (sourceRows == null || sourceRows.size() != 3) {
            return liquidityBlocked(now, rights, "LIQUIDITY_THREE_SOURCE_SNAPSHOTS_MISSING",
                1, 1, sourceRows == null ? 0 : sourceRows.size());
        }
        var rows = new ArrayList<FnoContractLiquidityGateV1.MarketSnapshot>();
        String provider = null;
        for (var row : sourceRows) {
            if (row == null || !"UPSTOX_V3".equals(row.provider())
                    || !chosen.instrumentKey().equals(row.instrumentKey())
                    || !chosen.underlyingKey().equals(row.underlyingKey())
                    || !chosen.expiry().equals(row.expiry())
                    || chosen.lotSize() != row.lotSize()
                    || !"FUTURE".equals(row.kind())
                    || (provider != null && !provider.equals(row.provider()))
                    || row.exchangeAt() == null || row.observedAt() == null
                    || row.oiFirstObservedAt() == null || row.volumeFirstObservedAt() == null) {
                return liquidityBlocked(now, rights,
                    "LIQUIDITY_SOURCE_ROWS_INCOMPLETE_OR_AMBIGUOUS", 1, 1, sourceRows.size());
            }
            provider = row.provider();
            rows.add(new FnoContractLiquidityGateV1.MarketSnapshot(
                row.instrumentKey(), row.provider(), row.exchangeAt(), row.observedAt(),
                row.bid(), row.ask(), row.bidDepthUnits(), row.askDepthUnits(),
                row.oiUnits(), row.cumulativeVolumeUnits(),
                row.oiFirstObservedAt(), row.volumeFirstObservedAt(),
                true, true, true, true));
        }
        var last = sourceRows.getLast();
        var quote = current.get();
        if (last.bid() == null || last.ask() == null
                || last.bid().compareTo(quote.bid()) != 0
                || last.ask().compareTo(quote.ask()) != 0
                || quote.exchangeAt() == null
                || java.time.Duration.between(last.exchangeAt(), quote.exchangeAt())
                    .abs().compareTo(java.time.Duration.ofSeconds(10)) > 0) {
            return liquidityBlocked(now, rights, "LIQUIDITY_SOURCE_LIVE_QUOTE_MISMATCH",
                1, 1, sourceRows.size());
        }
        var contract = new FnoContractLiquidityGateV1.Contract(
            chosen.instrumentKey(), chosen.underlyingKey(), chosen.expiry(),
            chosen.lotSize(), FnoContractLiquidityGateV1.DerivativeKind.FUTURE,
            null, true);
        var decision = FnoContractLiquidityGateV1.evaluate(
            contract, List.copyOf(rows), rights, LIQUIDITY_THRESHOLDS, now);
        return status(decision.strategyId(), decision.status().name(),
            decision.reasonCode(), now, provider, 1, 1, rows.size(),
            "THREE_SOURCE_OBSERVED_LIQUIDITY_SNAPSHOTS_NO_ORDER");
    }

    private static StrategyStatus liquidityBlocked(Instant now,
            FnoContractLiquidityGateV1.SourceRights rights,
            String reason, int contracts, int quotes, int sourceRows) {
        var blocked = FnoContractLiquidityGateV1.evaluate(
            null, List.of(), rights, LIQUIDITY_THRESHOLDS, now);
        return status(blocked.strategyId(), blocked.status().name(), reason,
            now, "NONE", contracts, quotes, sourceRows, "LIQUIDITY_SOURCE_BLOCKED");
    }


    /**
     * STR-180: five genuinely source-attested, as-of finalized near-cash-close
     * paired quotes of exactly ONE deliverable NSE_EQ and its unrolled NSE_FO
     * future, using explicit source-reviewed carry, dividends and delivery
     * costs. The quotes are indicative, NOT exchange settlement prices.
     */
    private StrategyStatus evaluateExpiryBasisReversion(Instant now, LocalDate today) {
        var rights = new FnoExpiryBasisReversionV1.SourceRights(
            researchRightsVerified && expiryBasisResearchRightsVerified,
            researchRightsVerified && expiryBasisResearchRightsVerified,
            contractDiscoveryVerified);
        if (!researchRightsVerified || !contractDiscoveryVerified
                || !expiryBasisResearchRightsVerified) {
            return expiryBasisBlocked(now, rights, "EXPIRY_BASIS_SOURCE_RIGHTS_MISSING", 0, 0);
        }
        if (expiryBasisStore == null
                || !basisUnderlyingKey.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")) {
            return expiryBasisBlocked(now, rights,
                "EXPIRY_BASIS_SPOT_CONTRACT_NOT_CONFIGURED", 0, 0);
        }
        var master = registry.status();
        if (master == null || !"LOADED".equals(master.state())
                || master.fetchedAt() == null || master.sha256() == null
                || master.sha256().isBlank() || master.fetchedAt().isAfter(now)
                || java.time.Duration.between(master.fetchedAt(), now).toHours() > 48) {
            return expiryBasisBlocked(now, rights,
                "EXPIRY_BASIS_INSTRUMENT_MASTER_NOT_CURRENT", 0, 0);
        }
        var pair = registry.nearFar(basisUnderlyingKey, today);
        if (pair.isEmpty() || !basisUnderlyingKey.equals(pair.get().near().underlyingKey())) {
            return expiryBasisBlocked(now, rights,
                "EXPIRY_BASIS_VERIFIED_STOCK_FUTURE_NOT_RESOLVED", 0, 0);
        }
        FuturesContract future = pair.get().near();
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, future.expiry());
        if (days < 3 || days > 14) {
            return expiryBasisBlocked(now, rights, "EXPIRY_BASIS_OUTSIDE_RESEARCH_DTE", 1, 0);
        }
        final List<FnoVerifiedExpiryBasisPairStore.PairProof> source;
        try {
            source = expiryBasisStore.readExactPair(basisUnderlyingKey, future.instrumentKey(),
                future.expiry(), future.lotSize(), today.minusDays(21), today, now);
        } catch (RuntimeException unavailable) {
            return expiryBasisBlocked(now, rights,
                "EXPIRY_BASIS_SOURCE_READ_UNAVAILABLE", 1, 0);
        }
        if (source == null || source.size() < 5) {
            return expiryBasisBlocked(now, rights,
                "EXPIRY_BASIS_FIVE_VERIFIED_SESSION_PAIRS_MISSING", 1,
                source == null ? 0 : source.size());
        }
        if (source.size() >= 40) {
            return expiryBasisBlocked(now, rights, "EXPIRY_BASIS_SOURCE_WINDOW_AMBIGUOUS",
                1, source.size());
        }
        String provider = null;
        LocalDate prev = null, listedFrom = null;
        String contractDigest = null, carryDigest = null;
        FnoExpiryBasisReversionV1.Assumptions assumptions = null;
        for (var row : source) {
            if (row == null || row.sessionDate() == null || row.listedFrom() == null
                    || row.assumptions() == null
                    || !basisUnderlyingKey.equals(row.spotKey())
                    || !future.instrumentKey().equals(row.futuresKey())
                    || !future.expiry().equals(row.expiry())
                    || future.lotSize() != row.lotSize()
                    || !row.deliverableSpotVerified()
                    || !FnoMarketDataProvider.isSessionHistorySource(row.provider())
                    || (provider != null && !provider.equals(row.provider()))
                    || (listedFrom != null && !listedFrom.equals(row.listedFrom()))
                    || (assumptions != null && !assumptions.equals(row.assumptions()))
                    || row.contractSourceDigest() == null
                    || row.carryCostSourceDigest() == null
                    || (contractDigest != null
                        && !contractDigest.equals(row.contractSourceDigest()))
                    || (carryDigest != null
                        && !carryDigest.equals(row.carryCostSourceDigest()))
                    || (prev != null && !prev.isBefore(row.sessionDate()))
                    || row.sessionDate().isAfter(today)
                    || !FnoExpiryBasisReversionV1.cashClose(row.sessionDate())
                        .equals(row.cashSessionCloseAt())
                    || !FnoExpiryBasisReversionV1.futuresClose(row.sessionDate())
                        .equals(row.futuresSessionCloseAt())) {
                return expiryBasisBlocked(now, rights,
                    "EXPIRY_BASIS_SOURCE_ROWS_INCOMPLETE_OR_AMBIGUOUS", 1, source.size());
            }
            provider = row.provider();
            listedFrom = row.listedFrom();
            contractDigest = row.contractSourceDigest();
            carryDigest = row.carryCostSourceDigest();
            assumptions = row.assumptions();
            prev = row.sessionDate();
        }
        var last = source.subList(source.size() - 5, source.size());
        List<FnoExpiryBasisReversionV1.CompletedPair> completed = new ArrayList<>();
        prev = null;
        for (var row : last) {
            boolean verifiedTrading = calendar.isTradingDay(row.sessionDate());
            if (prev != null) {
                for (LocalDate day = prev.plusDays(1); day.isBefore(row.sessionDate());
                        day = day.plusDays(1)) {
                    if (calendar.isTradingDay(day)) verifiedTrading = false;
                }
            }
            var spot = new FnoExpiryBasisReversionV1.Quote(
                row.spotBid(), row.spotAsk(), row.spotQuoteAt(),
                row.spotQuoteFirstObservedAt());
            var futures = new FnoExpiryBasisReversionV1.Quote(
                row.futuresBid(), row.futuresAsk(), row.futuresQuoteAt(),
                row.futuresQuoteFirstObservedAt());
            completed.add(new FnoExpiryBasisReversionV1.CompletedPair(
                row.sessionDate(), verifiedTrading, row.spotKey(), row.futuresKey(),
                row.provider(), spot, futures, true, true,
                row.spotFinalFirstAvailableAt(), row.futuresFinalFirstAvailableAt()));
            prev = row.sessionDate();
        }
        var contract = new FnoExpiryBasisReversionV1.Contract(
            basisUnderlyingKey, future.instrumentKey(), future.underlyingKey(),
            listedFrom, future.expiry(), future.lotSize(), true, true);
        var result = FnoExpiryBasisReversionV1.evaluate(
            contract, List.copyOf(completed), rights, assumptions, now);
        return status(result.strategyId(), result.status().name(),
            result.reasonCode(), now, provider, 1, 0, completed.size(),
            "VERIFIED_EXPIRY_BASIS_CASH_CLOSE_PAIRS_RESEARCH_NO_ORDER");
    }

    private static StrategyStatus expiryBasisBlocked(Instant now,
            FnoExpiryBasisReversionV1.SourceRights rights,
            String reason, int contracts, int history) {
        // An independent evaluator callsite exists even while source is denied.
        var decision = FnoExpiryBasisReversionV1.evaluate(
            null, List.of(), rights, null, now);
        return status(decision.strategyId(), decision.status().name(), reason, now,
            "NONE", contracts, 0, history, "EXPIRY_BASIS_VERIFIED_SOURCE_BLOCKED");
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
