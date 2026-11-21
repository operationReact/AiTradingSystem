package in.aitradingsystem.tradingrooms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * A read-only 43-strategy *readiness* registry, not a 43-strategy execution engine.
 * The authoritative manifest supplies identities and distinct required inputs.
 * STR-170..180 have distinct research evaluator calls; STR-180 needs original-time near-close spot/future pair and carry/delivery receipts.
 * STR-181..191 each have distinct true options research evaluator callsites, including 1x2 uncovered ratio tail-risk checks; STR-192/193 require independent original IV; STR-194/195 require original short-leg bid depth/account margin/tail risk; STR-196/197 use original four-leg option quotes and independent IV, but condor and butterfly have distinct OTM/central strike rules. STR-198 is a three-distinct-CALL four-unit 1x2x1 original-source research adapter. STR-199 requires two DIFFERENT expiries without a common-expiry payoff. STR-200 requires different strikes AND expiries with source-proven near OTM short and far ITM long. STR-201 requires 200+ original aligned NIFTY OHLC and 30-day source normalized ATM IV sessions. STR-202 requires an independent official event schedule and original pre/post 30d IV/spot snapshots, strictly retrospective.
 * No research status, including RESEARCH_CANDIDATE, authorizes PAPER/LIVE orders.
 */
@Service
public final class Fno43StrategyReadinessService {
    private static final Set<String> DIRECT =
        Set.of("strategy.STR-170", "strategy.STR-171", "strategy.STR-172", "strategy.STR-173", "strategy.STR-174", "strategy.STR-175", "strategy.STR-176", "strategy.STR-177", "strategy.STR-178", "strategy.STR-179", "strategy.STR-180", "strategy.STR-181", "strategy.STR-182", "strategy.STR-183", "strategy.STR-184", "strategy.STR-185", "strategy.STR-186", "strategy.STR-187", "strategy.STR-188", "strategy.STR-189", "strategy.STR-190", "strategy.STR-191", "strategy.STR-192", "strategy.STR-193", "strategy.STR-194", "strategy.STR-195", "strategy.STR-196", "strategy.STR-197", "strategy.STR-198", "strategy.STR-199", "strategy.STR-200", "strategy.STR-201", "strategy.STR-202", "strategy.STR-203", "strategy.STR-204", "strategy.STR-205", "strategy.STR-206", "strategy.STR-207", "strategy.STR-208", "strategy.STR-209", "strategy.STR-210", "strategy.STR-211", "strategy.STR-212");
    private static final Set<String> SIGNAL_ONLY = Set.of();
    private static final String MANIFEST = "/mind/capability-manifest.v1.json";

    private record Definition(String id, String name, String source, List<String> requiredInputs) {}

    public record StrategyRow(String strategyId, String name, String evaluatorSource,
                              List<String> requiredInputs, String integrationState,
                              String evaluatorStatus, String reasonCode,
                              Instant evaluatedAt, String quoteSource,
                              int contractsResolved, int freshQuotes, int completedHistorySessions,
                              boolean distinctEvaluatorObserved,
                              boolean independentlyVerifiedSourceRights,
                              boolean historicalDataE2EValidated,
                              boolean paperExecutionValidated, boolean liveExecutionValidated) {}

    public record Coverage(String contractVersion, Instant generatedAt, int manifestStrategies,
                           int distinctEvaluatorsObserved, int signalRoutesWithoutDistinctEvaluator,
                           int strategiesWithoutDirectEvaluatorAdapter,
                           int independentlyVerifiedSourceStrategies,
                           int historicalDataE2EValidated,
                           int paperExecutionValidated, int liveExecutionValidated,
                           List<StrategyRow> strategies) {}

    private final FnoFuturesResearchService futures;
    private final FnoOptionsResearchService options;
    private final FnoDebitSpreadResearchService debitSpreads;
    private final FnoCreditSpreadResearchService creditSpreads;
    private final FnoOwnedEquityOptionsResearchService ownedStockOptions;
    private final FnoEquityCollarResearchService collar;
    private final FnoRatioSpreadResearchService ratioSpreads;
    private final FnoStraddleResearchService straddle;
    private final FnoStrangleResearchService strangle;
    private final FnoShortStraddleResearchService shortStraddle;
    private final FnoShortStrangleResearchService shortStrangle;
    private final FnoIronCondorResearchService ironCondor;
    private final FnoIronButterflyResearchService ironButterfly;
    private final FnoLongButterflyResearchService longButterfly;
    private final FnoCalendarSpreadResearchService calendarSpread;
    private final FnoDiagonalSpreadResearchService diagonalSpread;
    private final FnoIvRankResearchService ivRank;
    private final FnoVolatilityCrushResearchService volatilityCrush;
    private final FnoGammaScalpingResearchService gammaScalp;
    private final FnoDeltaHedgeResearchService deltaHedge;
    private final FnoBetaHedgeOptionsResearchService betaHedge;
    private final FnoTailRiskPutHedgeResearchService tailHedge;
    private final FnoPutSpreadCollarResearchService putSpreadCollar;
    private final FnoDefinedRiskSpreadRollResearchService spreadRoll;
    private final FnoExpiryRiskNeutralizationResearchService expiryNeutral;
    private final FnoPortfolioProtectiveOverlayResearchService portfolioOverlay;
    private final FnoGreeksExposureRebalanceResearchService greeksRebalance;
    private final FnoSkewBasedHedgeResearchService skewHedge;
    private final List<Definition> definitions;

    @Autowired
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions,
            FnoEquityCollarResearchService collar,
            FnoRatioSpreadResearchService ratioSpreads,
            FnoStraddleResearchService straddle,
            FnoStrangleResearchService strangle,
            FnoShortStraddleResearchService shortStraddle,
            FnoShortStrangleResearchService shortStrangle,
            FnoIronCondorResearchService ironCondor,
            FnoIronButterflyResearchService ironButterfly,
            FnoLongButterflyResearchService longButterfly,
            FnoCalendarSpreadResearchService calendarSpread,
            FnoDiagonalSpreadResearchService diagonalSpread,
            FnoIvRankResearchService ivRank,
            FnoVolatilityCrushResearchService volatilityCrush,
            FnoGammaScalpingResearchService gammaScalp,
            FnoDeltaHedgeResearchService deltaHedge,
            FnoBetaHedgeOptionsResearchService betaHedge,
            FnoTailRiskPutHedgeResearchService tailHedge,
            FnoPutSpreadCollarResearchService putSpreadCollar,
            FnoDefinedRiskSpreadRollResearchService spreadRoll,
            FnoExpiryRiskNeutralizationResearchService expiryNeutral,
            FnoPortfolioProtectiveOverlayResearchService portfolioOverlay,
            FnoGreeksExposureRebalanceResearchService greeksRebalance,
            FnoSkewBasedHedgeResearchService skewHedge,
            ObjectMapper mapper) {
        this.futures = Objects.requireNonNull(futures);
        this.options = Objects.requireNonNull(options);
        this.debitSpreads = Objects.requireNonNull(debitSpreads);
        this.creditSpreads = Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions = Objects.requireNonNull(ownedStockOptions);
        this.collar = Objects.requireNonNull(collar);
        this.ratioSpreads = Objects.requireNonNull(ratioSpreads);
        this.straddle = Objects.requireNonNull(straddle);
        this.strangle = Objects.requireNonNull(strangle);
        this.shortStraddle = Objects.requireNonNull(shortStraddle);
        this.shortStrangle = Objects.requireNonNull(shortStrangle);
        this.ironCondor = Objects.requireNonNull(ironCondor);
        this.ironButterfly = Objects.requireNonNull(ironButterfly);
        this.longButterfly = Objects.requireNonNull(longButterfly);
        this.calendarSpread = Objects.requireNonNull(calendarSpread);
        this.diagonalSpread = Objects.requireNonNull(diagonalSpread);
        this.ivRank = Objects.requireNonNull(ivRank);
        this.volatilityCrush = Objects.requireNonNull(volatilityCrush);
        this.gammaScalp = Objects.requireNonNull(gammaScalp);
        this.deltaHedge = Objects.requireNonNull(deltaHedge);
        this.betaHedge = Objects.requireNonNull(betaHedge);
        this.tailHedge = Objects.requireNonNull(tailHedge);
        this.putSpreadCollar = Objects.requireNonNull(putSpreadCollar);
        this.spreadRoll = Objects.requireNonNull(spreadRoll);
        this.expiryNeutral = Objects.requireNonNull(expiryNeutral);
        this.portfolioOverlay = Objects.requireNonNull(portfolioOverlay);
        this.greeksRebalance = Objects.requireNonNull(greeksRebalance);
        this.skewHedge = Objects.requireNonNull(skewHedge);
        this.definitions = readManifest(Objects.requireNonNull(mapper));
    }

    /** Existing STR-206 fixture constructor retained for regression tests. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions,
            FnoEquityCollarResearchService collar,
            FnoRatioSpreadResearchService ratioSpreads,
            FnoStraddleResearchService straddle,
            FnoStrangleResearchService strangle,
            FnoShortStraddleResearchService shortStraddle,
            FnoShortStrangleResearchService shortStrangle,
            FnoIronCondorResearchService ironCondor,
            FnoIronButterflyResearchService ironButterfly,
            FnoLongButterflyResearchService longButterfly,
            FnoCalendarSpreadResearchService calendarSpread,
            FnoDiagonalSpreadResearchService diagonalSpread,
            FnoIvRankResearchService ivRank,
            FnoVolatilityCrushResearchService volatilityCrush,
            FnoGammaScalpingResearchService gammaScalp,
            FnoDeltaHedgeResearchService deltaHedge,
            FnoBetaHedgeOptionsResearchService betaHedge,
            FnoTailRiskPutHedgeResearchService tailHedge,
            ObjectMapper mapper) {
        this.futures = Objects.requireNonNull(futures);
        this.options = Objects.requireNonNull(options);
        this.debitSpreads = Objects.requireNonNull(debitSpreads);
        this.creditSpreads = Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions = Objects.requireNonNull(ownedStockOptions);
        this.collar = Objects.requireNonNull(collar);
        this.ratioSpreads = Objects.requireNonNull(ratioSpreads);
        this.straddle = Objects.requireNonNull(straddle);
        this.strangle = Objects.requireNonNull(strangle);
        this.shortStraddle = Objects.requireNonNull(shortStraddle);
        this.shortStrangle = Objects.requireNonNull(shortStrangle);
        this.ironCondor = Objects.requireNonNull(ironCondor);
        this.ironButterfly = Objects.requireNonNull(ironButterfly);
        this.longButterfly = Objects.requireNonNull(longButterfly);
        this.calendarSpread = Objects.requireNonNull(calendarSpread);
        this.diagonalSpread = Objects.requireNonNull(diagonalSpread);
        this.ivRank = Objects.requireNonNull(ivRank);
        this.volatilityCrush = Objects.requireNonNull(volatilityCrush);
        this.gammaScalp = Objects.requireNonNull(gammaScalp);
        this.deltaHedge = Objects.requireNonNull(deltaHedge);
        this.betaHedge = Objects.requireNonNull(betaHedge);
        this.tailHedge = Objects.requireNonNull(tailHedge);
        this.putSpreadCollar = null;
        this.spreadRoll = null;
        this.expiryNeutral = null;
        this.portfolioOverlay = null;
        this.greeksRebalance = null;
        this.skewHedge = null;
        this.definitions = readManifest(Objects.requireNonNull(mapper));
    }

    /** Legacy STR-202 fixture constructor without four new research adapters. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions,
            FnoEquityCollarResearchService collar,
            FnoRatioSpreadResearchService ratioSpreads,
            FnoStraddleResearchService straddle,
            FnoStrangleResearchService strangle,
            FnoShortStraddleResearchService shortStraddle,
            FnoShortStrangleResearchService shortStrangle,
            FnoIronCondorResearchService ironCondor,
            FnoIronButterflyResearchService ironButterfly,
            FnoLongButterflyResearchService longButterfly,
            FnoCalendarSpreadResearchService calendarSpread,
            FnoDiagonalSpreadResearchService diagonalSpread,
            FnoIvRankResearchService ivRank,
            FnoVolatilityCrushResearchService volatilityCrush,ObjectMapper mapper) {
        this.futures=Objects.requireNonNull(futures);
        this.options=Objects.requireNonNull(options);
        this.debitSpreads=Objects.requireNonNull(debitSpreads);
        this.creditSpreads=Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions=Objects.requireNonNull(ownedStockOptions);
        this.collar=Objects.requireNonNull(collar);
        this.ratioSpreads=Objects.requireNonNull(ratioSpreads);
        this.straddle=Objects.requireNonNull(straddle);
        this.strangle=Objects.requireNonNull(strangle);
        this.shortStraddle=Objects.requireNonNull(shortStraddle);
        this.shortStrangle=Objects.requireNonNull(shortStrangle);
        this.ironCondor=Objects.requireNonNull(ironCondor);
        this.ironButterfly=Objects.requireNonNull(ironButterfly);
        this.longButterfly=Objects.requireNonNull(longButterfly);
        this.calendarSpread=Objects.requireNonNull(calendarSpread);
        this.diagonalSpread=Objects.requireNonNull(diagonalSpread);
        this.ivRank=Objects.requireNonNull(ivRank);
        this.volatilityCrush=Objects.requireNonNull(volatilityCrush);
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions=readManifest(Objects.requireNonNull(mapper));
    }

    /** Compatibility with STR-201 fixture before original event-anchored IV crush. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions,
            FnoEquityCollarResearchService collar,
            FnoRatioSpreadResearchService ratioSpreads,
            FnoStraddleResearchService straddle,
            FnoStrangleResearchService strangle,
            FnoShortStraddleResearchService shortStraddle,
            FnoShortStrangleResearchService shortStrangle,
            FnoIronCondorResearchService ironCondor,
            FnoIronButterflyResearchService ironButterfly,
            FnoLongButterflyResearchService longButterfly,
            FnoCalendarSpreadResearchService calendarSpread,
            FnoDiagonalSpreadResearchService diagonalSpread,
            FnoIvRankResearchService ivRank, ObjectMapper mapper) {
        this.futures=Objects.requireNonNull(futures);
        this.options=Objects.requireNonNull(options);
        this.debitSpreads=Objects.requireNonNull(debitSpreads);
        this.creditSpreads=Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions=Objects.requireNonNull(ownedStockOptions);
        this.collar=Objects.requireNonNull(collar);
        this.ratioSpreads=Objects.requireNonNull(ratioSpreads);
        this.straddle=Objects.requireNonNull(straddle);
        this.strangle=Objects.requireNonNull(strangle);
        this.shortStraddle=Objects.requireNonNull(shortStraddle);
        this.shortStrangle=Objects.requireNonNull(shortStrangle);
        this.ironCondor=Objects.requireNonNull(ironCondor);
        this.ironButterfly=Objects.requireNonNull(ironButterfly);
        this.longButterfly=Objects.requireNonNull(longButterfly);
        this.calendarSpread=Objects.requireNonNull(calendarSpread);
        this.diagonalSpread=Objects.requireNonNull(diagonalSpread);
        this.ivRank=Objects.requireNonNull(ivRank);
        this.volatilityCrush=null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions=readManifest(Objects.requireNonNull(mapper));
    }

    /** Compatibility with STR-200 fixture before independently verified original 30d IV rank. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions,
            FnoEquityCollarResearchService collar,
            FnoRatioSpreadResearchService ratioSpreads,
            FnoStraddleResearchService straddle,
            FnoStrangleResearchService strangle,
            FnoShortStraddleResearchService shortStraddle,
            FnoShortStrangleResearchService shortStrangle,
            FnoIronCondorResearchService ironCondor,
            FnoIronButterflyResearchService ironButterfly,
            FnoLongButterflyResearchService longButterfly,
            FnoCalendarSpreadResearchService calendarSpread,
            FnoDiagonalSpreadResearchService diagonalSpread, ObjectMapper mapper) {
        this.futures=Objects.requireNonNull(futures);
        this.options=Objects.requireNonNull(options);
        this.debitSpreads=Objects.requireNonNull(debitSpreads);
        this.creditSpreads=Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions=Objects.requireNonNull(ownedStockOptions);
        this.collar=Objects.requireNonNull(collar);
        this.ratioSpreads=Objects.requireNonNull(ratioSpreads);
        this.straddle=Objects.requireNonNull(straddle);
        this.strangle=Objects.requireNonNull(strangle);
        this.shortStraddle=Objects.requireNonNull(shortStraddle);
        this.shortStrangle=Objects.requireNonNull(shortStrangle);
        this.ironCondor=Objects.requireNonNull(ironCondor);
        this.ironButterfly=Objects.requireNonNull(ironButterfly);
        this.longButterfly=Objects.requireNonNull(longButterfly);
        this.calendarSpread=Objects.requireNonNull(calendarSpread);
        this.diagonalSpread=Objects.requireNonNull(diagonalSpread);
        this.ivRank=null;
        this.volatilityCrush=null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions=readManifest(Objects.requireNonNull(mapper));
    }

    /** Compatibility with STR-199 fixture before different-strike diagonal source. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions,
            FnoEquityCollarResearchService collar,
            FnoRatioSpreadResearchService ratioSpreads,
            FnoStraddleResearchService straddle,
            FnoStrangleResearchService strangle,
            FnoShortStraddleResearchService shortStraddle,
            FnoShortStrangleResearchService shortStrangle,
            FnoIronCondorResearchService ironCondor,
            FnoIronButterflyResearchService ironButterfly,
            FnoLongButterflyResearchService longButterfly,
            FnoCalendarSpreadResearchService calendarSpread, ObjectMapper mapper) {
        this.futures=Objects.requireNonNull(futures);
        this.options=Objects.requireNonNull(options);
        this.debitSpreads=Objects.requireNonNull(debitSpreads);
        this.creditSpreads=Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions=Objects.requireNonNull(ownedStockOptions);
        this.collar=Objects.requireNonNull(collar);
        this.ratioSpreads=Objects.requireNonNull(ratioSpreads);
        this.straddle=Objects.requireNonNull(straddle);
        this.strangle=Objects.requireNonNull(strangle);
        this.shortStraddle=Objects.requireNonNull(shortStraddle);
        this.shortStrangle=Objects.requireNonNull(shortStrangle);
        this.ironCondor=Objects.requireNonNull(ironCondor);
        this.ironButterfly=Objects.requireNonNull(ironButterfly);
        this.longButterfly=Objects.requireNonNull(longButterfly);
        this.calendarSpread=Objects.requireNonNull(calendarSpread);
        this.diagonalSpread=null;
        this.ivRank=null;
        this.volatilityCrush=null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions=readManifest(Objects.requireNonNull(mapper));
    }

    /** Compatibility with STR-198 fixture before original cross-expiry calendar source. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions,
            FnoEquityCollarResearchService collar,
            FnoRatioSpreadResearchService ratioSpreads,
            FnoStraddleResearchService straddle,
            FnoStrangleResearchService strangle,
            FnoShortStraddleResearchService shortStraddle,
            FnoShortStrangleResearchService shortStrangle,
            FnoIronCondorResearchService ironCondor,
            FnoIronButterflyResearchService ironButterfly,
            FnoLongButterflyResearchService longButterfly, ObjectMapper mapper) {
        this.futures=Objects.requireNonNull(futures);
        this.options=Objects.requireNonNull(options);
        this.debitSpreads=Objects.requireNonNull(debitSpreads);
        this.creditSpreads=Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions=Objects.requireNonNull(ownedStockOptions);
        this.collar=Objects.requireNonNull(collar);
        this.ratioSpreads=Objects.requireNonNull(ratioSpreads);
        this.straddle=Objects.requireNonNull(straddle);
        this.strangle=Objects.requireNonNull(strangle);
        this.shortStraddle=Objects.requireNonNull(shortStraddle);
        this.shortStrangle=Objects.requireNonNull(shortStrangle);
        this.ironCondor=Objects.requireNonNull(ironCondor);
        this.ironButterfly=Objects.requireNonNull(ironButterfly);
        this.longButterfly=Objects.requireNonNull(longButterfly);
        this.calendarSpread=null;
        this.diagonalSpread=null;
        this.ivRank=null;
        this.volatilityCrush=null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions=readManifest(Objects.requireNonNull(mapper));
    }

    /** Compatibility with STR-197 fixtures before the 1x2x1 long call butterfly source. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions,
            FnoEquityCollarResearchService collar,
            FnoRatioSpreadResearchService ratioSpreads,
            FnoStraddleResearchService straddle,
            FnoStrangleResearchService strangle,
            FnoShortStraddleResearchService shortStraddle,
            FnoShortStrangleResearchService shortStrangle,
            FnoIronCondorResearchService ironCondor,
            FnoIronButterflyResearchService ironButterfly, ObjectMapper mapper) {
        this.futures=Objects.requireNonNull(futures);
        this.options=Objects.requireNonNull(options);
        this.debitSpreads=Objects.requireNonNull(debitSpreads);
        this.creditSpreads=Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions=Objects.requireNonNull(ownedStockOptions);
        this.collar=Objects.requireNonNull(collar);
        this.ratioSpreads=Objects.requireNonNull(ratioSpreads);
        this.straddle=Objects.requireNonNull(straddle);
        this.strangle=Objects.requireNonNull(strangle);
        this.shortStraddle=Objects.requireNonNull(shortStraddle);
        this.shortStrangle=Objects.requireNonNull(shortStrangle);
        this.ironCondor=Objects.requireNonNull(ironCondor);
        this.ironButterfly=Objects.requireNonNull(ironButterfly);
        this.longButterfly=null;
        this.calendarSpread=null;
        this.diagonalSpread=null;
        this.ivRank=null;
        this.volatilityCrush=null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions=readManifest(Objects.requireNonNull(mapper));
    }

    /** Compatibility for STR-196 fixtures before centered iron-butterfly source custody. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions,
            FnoEquityCollarResearchService collar,
            FnoRatioSpreadResearchService ratioSpreads,
            FnoStraddleResearchService straddle,
            FnoStrangleResearchService strangle,
            FnoShortStraddleResearchService shortStraddle,
            FnoShortStrangleResearchService shortStrangle,
            FnoIronCondorResearchService ironCondor, ObjectMapper mapper) {
        this.futures=Objects.requireNonNull(futures);
        this.options=Objects.requireNonNull(options);
        this.debitSpreads=Objects.requireNonNull(debitSpreads);
        this.creditSpreads=Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions=Objects.requireNonNull(ownedStockOptions);
        this.collar=Objects.requireNonNull(collar);
        this.ratioSpreads=Objects.requireNonNull(ratioSpreads);
        this.straddle=Objects.requireNonNull(straddle);
        this.strangle=Objects.requireNonNull(strangle);
        this.shortStraddle=Objects.requireNonNull(shortStraddle);
        this.shortStrangle=Objects.requireNonNull(shortStrangle);
        this.ironCondor=Objects.requireNonNull(ironCondor);
        this.ironButterfly=null;
        this.longButterfly=null;
        this.calendarSpread=null;
        this.diagonalSpread=null;
        this.ivRank=null;
        this.volatilityCrush=null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions=readManifest(Objects.requireNonNull(mapper));
    }

    /** Compatibility with STR-195 fixtures without four-leg iron-condor custody. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions,
            FnoEquityCollarResearchService collar,
            FnoRatioSpreadResearchService ratioSpreads,
            FnoStraddleResearchService straddle,
            FnoStrangleResearchService strangle,
            FnoShortStraddleResearchService shortStraddle,
            FnoShortStrangleResearchService shortStrangle, ObjectMapper mapper) {
        this.futures=Objects.requireNonNull(futures);
        this.options=Objects.requireNonNull(options);
        this.debitSpreads=Objects.requireNonNull(debitSpreads);
        this.creditSpreads=Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions=Objects.requireNonNull(ownedStockOptions);
        this.collar=Objects.requireNonNull(collar);
        this.ratioSpreads=Objects.requireNonNull(ratioSpreads);
        this.straddle=Objects.requireNonNull(straddle);
        this.strangle=Objects.requireNonNull(strangle);
        this.shortStraddle=Objects.requireNonNull(shortStraddle);
        this.shortStrangle=Objects.requireNonNull(shortStrangle);
        this.ironCondor=null;
        this.ironButterfly=null;
        this.longButterfly=null;
        this.calendarSpread=null;
        this.diagonalSpread=null;
        this.ivRank=null;
        this.volatilityCrush=null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions=readManifest(Objects.requireNonNull(mapper));
    }

    /** Compatibility with STR-194 fixtures before out-of-money short strangle custody. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions,
            FnoEquityCollarResearchService collar,
            FnoRatioSpreadResearchService ratioSpreads,
            FnoStraddleResearchService straddle,
            FnoStrangleResearchService strangle,
            FnoShortStraddleResearchService shortStraddle, ObjectMapper mapper) {
        this.futures=Objects.requireNonNull(futures);
        this.options=Objects.requireNonNull(options);
        this.debitSpreads=Objects.requireNonNull(debitSpreads);
        this.creditSpreads=Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions=Objects.requireNonNull(ownedStockOptions);
        this.collar=Objects.requireNonNull(collar);
        this.ratioSpreads=Objects.requireNonNull(ratioSpreads);
        this.straddle=Objects.requireNonNull(straddle);
        this.strangle=Objects.requireNonNull(strangle);
        this.shortStraddle=Objects.requireNonNull(shortStraddle);
        this.shortStrangle=null;
        this.ironCondor=null;
        this.ironButterfly=null;
        this.longButterfly=null;
        this.calendarSpread=null;
        this.diagonalSpread=null;
        this.ivRank=null;
        this.volatilityCrush=null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions=readManifest(Objects.requireNonNull(mapper));
    }

    /** Compatibility with the STR-193 fixture before naked-short source custody was added. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions,
            FnoEquityCollarResearchService collar,
            FnoRatioSpreadResearchService ratioSpreads,
            FnoStraddleResearchService straddle,
            FnoStrangleResearchService strangle, ObjectMapper mapper) {
        this.futures=Objects.requireNonNull(futures);
        this.options=Objects.requireNonNull(options);
        this.debitSpreads=Objects.requireNonNull(debitSpreads);
        this.creditSpreads=Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions=Objects.requireNonNull(ownedStockOptions);
        this.collar=Objects.requireNonNull(collar);
        this.ratioSpreads=Objects.requireNonNull(ratioSpreads);
        this.straddle=Objects.requireNonNull(straddle);
        this.strangle=Objects.requireNonNull(strangle);
        this.shortStraddle=null;
        this.shortStrangle=null;
        this.ironCondor=null;
        this.ironButterfly=null;
        this.longButterfly=null;
        this.calendarSpread=null;
        this.diagonalSpread=null;
        this.ivRank=null;
        this.volatilityCrush=null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions=readManifest(Objects.requireNonNull(mapper));
    }

    /** Compatibility for STR-192 fixtures without the OTM strangle source store. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions,
            FnoEquityCollarResearchService collar,
            FnoRatioSpreadResearchService ratioSpreads,
            FnoStraddleResearchService straddle, ObjectMapper mapper) {
        this.futures=Objects.requireNonNull(futures);
        this.options=Objects.requireNonNull(options);
        this.debitSpreads=Objects.requireNonNull(debitSpreads);
        this.creditSpreads=Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions=Objects.requireNonNull(ownedStockOptions);
        this.collar=Objects.requireNonNull(collar);
        this.ratioSpreads=Objects.requireNonNull(ratioSpreads);
        this.straddle=Objects.requireNonNull(straddle);
        this.strangle=null;
        this.shortStraddle=null;
        this.shortStrangle=null;
        this.ironCondor=null;
        this.ironButterfly=null;
        this.longButterfly=null;
        this.calendarSpread=null;
        this.diagonalSpread=null;
        this.ivRank=null;
        this.volatilityCrush=null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions=readManifest(Objects.requireNonNull(mapper));
    }

    /** Compatibility for ratio-spread integration fixtures without the IV custody store. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions,
            FnoEquityCollarResearchService collar,
            FnoRatioSpreadResearchService ratioSpreads, ObjectMapper mapper) {
        this.futures=Objects.requireNonNull(futures);
        this.options=Objects.requireNonNull(options);
        this.debitSpreads=Objects.requireNonNull(debitSpreads);
        this.creditSpreads=Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions=Objects.requireNonNull(ownedStockOptions);
        this.collar=Objects.requireNonNull(collar);
        this.ratioSpreads=Objects.requireNonNull(ratioSpreads);
        this.straddle=null;
        this.strangle=null;
        this.shortStraddle=null;
        this.shortStrangle=null;
        this.ironCondor=null;
        this.ironButterfly=null;
        this.longButterfly=null;
        this.calendarSpread=null;
        this.diagonalSpread=null;
        this.ivRank=null;
        this.volatilityCrush=null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions=readManifest(Objects.requireNonNull(mapper));
    }

    /** Compatibility with the STR-189 audited collar-only research fixture. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions,
            FnoEquityCollarResearchService collar, ObjectMapper mapper) {
        this.futures=Objects.requireNonNull(futures);
        this.options=Objects.requireNonNull(options);
        this.debitSpreads=Objects.requireNonNull(debitSpreads);
        this.creditSpreads=Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions=Objects.requireNonNull(ownedStockOptions);
        this.collar=Objects.requireNonNull(collar);
        this.ratioSpreads=null;
        this.straddle=null;
        this.strangle=null;
        this.shortStraddle=null;
        this.shortStrangle=null;
        this.ironCondor=null;
        this.ironButterfly=null;
        this.longButterfly=null;
        this.calendarSpread=null;
        this.diagonalSpread=null;
        this.ivRank=null;
        this.volatilityCrush=null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions=readManifest(Objects.requireNonNull(mapper));
    }

    /** Compatibility with independent settled-stock evidence fixtures. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads,
            FnoOwnedEquityOptionsResearchService ownedStockOptions, ObjectMapper mapper) {
        this.futures = Objects.requireNonNull(futures);
        this.options = Objects.requireNonNull(options);
        this.debitSpreads = Objects.requireNonNull(debitSpreads);
        this.creditSpreads = Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions = Objects.requireNonNull(ownedStockOptions);
        this.collar = null;
        this.ratioSpreads = null;
        this.straddle = null;
        this.strangle = null;
        this.shortStraddle = null;
        this.shortStrangle = null;
        this.ironCondor = null;
        this.ironButterfly = null;
        this.longButterfly = null;
        this.calendarSpread = null;
        this.diagonalSpread = null;
        this.ivRank = null;
        this.volatilityCrush = null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions = readManifest(Objects.requireNonNull(mapper));
    }

    /** Compatibility for isolated per-adapter JUnit fixtures. */
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads,
            FnoCreditSpreadResearchService creditSpreads, ObjectMapper mapper) {
        this.futures = Objects.requireNonNull(futures);
        this.options = Objects.requireNonNull(options);
        this.debitSpreads = Objects.requireNonNull(debitSpreads);
        this.creditSpreads = Objects.requireNonNull(creditSpreads);
        this.ownedStockOptions = null;
        this.collar = null;
        this.ratioSpreads = null;
        this.straddle = null;
        this.strangle = null;
        this.shortStraddle = null;
        this.shortStrangle = null;
        this.ironCondor = null;
        this.ironButterfly = null;
        this.longButterfly = null;
        this.calendarSpread = null;
        this.diagonalSpread = null;
        this.ivRank = null;
        this.volatilityCrush = null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions = readManifest(Objects.requireNonNull(mapper));
    }
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options,
            FnoDebitSpreadResearchService debitSpreads, ObjectMapper mapper) {
        this.futures = Objects.requireNonNull(futures);
        this.options = Objects.requireNonNull(options);
        this.debitSpreads = Objects.requireNonNull(debitSpreads);
        this.creditSpreads = null;
        this.ownedStockOptions = null;
        this.collar = null;
        this.ratioSpreads = null;
        this.straddle = null;
        this.strangle = null;
        this.shortStraddle = null;
        this.shortStrangle = null;
        this.ironCondor = null;
        this.ironButterfly = null;
        this.longButterfly = null;
        this.calendarSpread = null;
        this.diagonalSpread = null;
        this.ivRank = null;
        this.volatilityCrush = null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions = readManifest(Objects.requireNonNull(mapper));
    }
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            FnoOptionsResearchService options, ObjectMapper mapper) {
        this.futures = Objects.requireNonNull(futures);
        this.options = Objects.requireNonNull(options);
        this.debitSpreads = null;
        this.creditSpreads = null;
        this.ownedStockOptions = null;
        this.collar = null;
        this.ratioSpreads = null;
        this.straddle = null;
        this.strangle = null;
        this.shortStraddle = null;
        this.shortStrangle = null;
        this.ironCondor = null;
        this.ironButterfly = null;
        this.longButterfly = null;
        this.calendarSpread = null;
        this.diagonalSpread = null;
        this.ivRank = null;
        this.volatilityCrush = null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions = readManifest(Objects.requireNonNull(mapper));
    }
    public Fno43StrategyReadinessService(FnoFuturesResearchService futures,
            ObjectMapper mapper) {
        this.futures = Objects.requireNonNull(futures);
        this.options = null;
        this.debitSpreads = null;
        this.creditSpreads = null;
        this.ownedStockOptions = null;
        this.collar = null;
        this.ratioSpreads = null;
        this.straddle = null;
        this.strangle = null;
        this.shortStraddle = null;
        this.shortStrangle = null;
        this.ironCondor = null;
        this.ironButterfly = null;
        this.longButterfly = null;
        this.calendarSpread = null;
        this.diagonalSpread = null;
        this.ivRank = null;
        this.volatilityCrush = null;
        this.gammaScalp=null;
        this.deltaHedge=null;
        this.betaHedge=null;
        this.tailHedge=null;
        this.putSpreadCollar=null;
        this.spreadRoll=null;
        this.expiryNeutral=null;
        this.portfolioOverlay=null;
        this.greeksRebalance=null;
        this.skewHedge=null;
        this.definitions = readManifest(Objects.requireNonNull(mapper));
    }

    /** Never persists inputs, touches broker order services or grants permissions. */
    public Coverage inspect(Instant now) {
        Objects.requireNonNull(now);
        Map<String, FnoFuturesResearchService.StrategyStatus> observed = new HashMap<>();
        try {
            List<FnoFuturesResearchService.StrategyStatus> futuresRows = futures.evaluateAll(now);
            if (futuresRows != null) for (var status : futuresRows) {
                if (status != null && DIRECT.contains(status.strategyId())
                        && status.status() != null && status.reasonCode() != null) {
                    // Conflicting/duplicate runtime evidence is treated as unavailable.
                    if (observed.putIfAbsent(status.strategyId(), status) != null) {
                        observed.clear();
                        break;
                    }
                }
            }
        } catch (RuntimeException unavailable) {
            // Fail closed. Do not expose provider exceptions or token-bearing messages.
            observed.clear();
        }

        if (options != null) {
            try {
                var optionRows = options.evaluateAll(now);
                if (optionRows != null) for (var result : optionRows) {
                    if (result != null && DIRECT.contains(result.strategyId())
                            && ("strategy.STR-181".equals(result.strategyId())
                                || "strategy.STR-182".equals(result.strategyId()))
                            && result.status() != null && result.reasonCode() != null) {
                        if (observed.putIfAbsent(result.strategyId(), result) != null) {
                            // Never choose from conflicting duplicate source observations.
                            observed.remove(result.strategyId());
                        }
                    }
                }
            } catch (RuntimeException unavailable) {
                // Preserve independently observed futures rows; source failure is
                // not proof of option readiness and must not expose provider bodies.
                observed.remove("strategy.STR-181");
                observed.remove("strategy.STR-182");
            }
        }

        if (debitSpreads != null) {
            try {
                var spreads = debitSpreads.evaluateAll(now);
                if (spreads != null) for (var result : spreads) {
                    if (result != null && DIRECT.contains(result.strategyId())
                            && ("strategy.STR-183".equals(result.strategyId())
                                || "strategy.STR-184".equals(result.strategyId()))
                            && result.status() != null && result.reasonCode() != null) {
                        if (observed.putIfAbsent(result.strategyId(), result) != null) {
                            observed.remove(result.strategyId());
                        }
                    }
                }
            } catch (RuntimeException unavailable) {
                // Fail closed for this source only; do not hide independent
                // futures or single-leg options evaluator observations.
                observed.remove("strategy.STR-183");
                observed.remove("strategy.STR-184");
            }
        }

        if (creditSpreads != null) {
            try {
                var creditRows = creditSpreads.evaluateAll(now);
                if (creditRows != null) for (var result : creditRows) {
                    if (result != null && DIRECT.contains(result.strategyId())
                            && ("strategy.STR-185".equals(result.strategyId())
                                || "strategy.STR-186".equals(result.strategyId()))
                            && result.status() != null && result.reasonCode() != null) {
                        if (observed.putIfAbsent(result.strategyId(), result) != null) {
                            observed.remove(result.strategyId());
                        }
                    }
                }
            } catch (RuntimeException unavailable) {
                observed.remove("strategy.STR-185");
                observed.remove("strategy.STR-186");
            }
        }

        if (ownedStockOptions != null) {
            try {
                var sourceRows = ownedStockOptions.evaluateAll(now);
                if (sourceRows != null) for (var result : sourceRows) {
                    if (result != null && DIRECT.contains(result.strategyId())
                            && ("strategy.STR-187".equals(result.strategyId())
                                || "strategy.STR-188".equals(result.strategyId()))
                            && result.status() != null && result.reasonCode() != null) {
                        if (observed.putIfAbsent(result.strategyId(), result) != null)
                            observed.remove(result.strategyId());
                    }
                }
            } catch (RuntimeException unavailable) {
                observed.remove("strategy.STR-187");
                observed.remove("strategy.STR-188");
            }
        }

        if (collar != null) {
            try {
                var result = collar.evaluate(now);
                if (result != null && DIRECT.contains(result.strategyId())
                        && "strategy.STR-189".equals(result.strategyId())
                        && result.status() != null && result.reasonCode() != null) {
                    if (observed.putIfAbsent(result.strategyId(),result) != null)
                        observed.remove(result.strategyId());
                }
            } catch (RuntimeException ignored) {
                observed.remove("strategy.STR-189");
            }
        }

        if (ratioSpreads != null) {
            try {
                var sourceRows = ratioSpreads.evaluateAll(now);
                if (sourceRows != null) for (var result:sourceRows) {
                    if (result != null && DIRECT.contains(result.strategyId())
                            && ("strategy.STR-190".equals(result.strategyId())
                                || "strategy.STR-191".equals(result.strategyId()))
                            && result.status()!=null && result.reasonCode()!=null) {
                        if(observed.putIfAbsent(result.strategyId(),result)!=null)
                            observed.remove(result.strategyId());
                    }
                }
            } catch (RuntimeException unavailable) {
                observed.remove("strategy.STR-190");
                observed.remove("strategy.STR-191");
            }
        }


        if (straddle != null) {
            try {
                var result = straddle.evaluate(now);
                if (result != null && "strategy.STR-192".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null) {
                    if (observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            } catch (RuntimeException unavailable) {
                observed.remove("strategy.STR-192");
            }
        }

        if (strangle != null) {
            try {
                var result = strangle.evaluate(now);
                if (result != null && "strategy.STR-193".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null) {
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            } catch (RuntimeException unavailable) {
                observed.remove("strategy.STR-193");
            }
        }

        if (shortStraddle != null) {
            try {
                var result = shortStraddle.evaluate(now);
                if (result != null && "strategy.STR-194".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null) {
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            } catch (RuntimeException unavailable) {
                observed.remove("strategy.STR-194");
            }
        }

        if (shortStrangle != null) {
            try {
                var result=shortStrangle.evaluate(now);
                if (result!=null && "strategy.STR-195".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null) {
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            } catch (RuntimeException unavailable) {
                observed.remove("strategy.STR-195");
            }
        }

        if (ironCondor != null) {
            try {
                var result=ironCondor.evaluate(now);
                if(result!=null && "strategy.STR-196".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null) {
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            } catch(RuntimeException unavailable){
                observed.remove("strategy.STR-196");
            }
        }

        if(ironButterfly!=null){
            try{
                var result=ironButterfly.evaluate(now);
                if(result!=null && "strategy.STR-197".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-197");
            }
        }

        if(longButterfly!=null){
            try {
                var result=longButterfly.evaluate(now);
                if(result!=null && "strategy.STR-198".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-198");
            }
        }

        if(calendarSpread!=null){
            try {
                var result=calendarSpread.evaluate(now);
                if(result!=null && "strategy.STR-199".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-199");
            }
        }

        if(diagonalSpread!=null){
            try {
                var result=diagonalSpread.evaluate(now);
                if(result!=null && "strategy.STR-200".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-200");
            }
        }

        if(ivRank!=null){
            try {
                var result=ivRank.evaluate(now);
                if(result!=null && "strategy.STR-201".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-201");
            }
        }

        if(volatilityCrush!=null){
            try {
                var result=volatilityCrush.evaluate(now);
                if(result!=null && "strategy.STR-202".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-202");
            }
        }


        if(gammaScalp!=null){
            try{
                var result=gammaScalp.evaluate(now);
                if(result!=null && "strategy.STR-203".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-203");
            }
        }


        if(deltaHedge!=null){
            try{
                var result=deltaHedge.evaluate(now);
                if(result!=null && "strategy.STR-204".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-204");
            }
        }


        if(betaHedge!=null){
            try{
                var result=betaHedge.evaluate(now);
                if(result!=null && "strategy.STR-205".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-205");
            }
        }


        if(tailHedge!=null){
            try{
                var result=tailHedge.evaluate(now);
                if(result!=null && "strategy.STR-206".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-206");
            }
        }

        if(putSpreadCollar!=null){
            try{
                var result=putSpreadCollar.evaluate(now);
                if(result!=null && "strategy.STR-207".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-207");
            }
        }

        if(spreadRoll!=null){
            try{
                var result=spreadRoll.evaluate(now);
                if(result!=null && "strategy.STR-208".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-208");
            }
        }

        if(expiryNeutral!=null){
            try{
                var result=expiryNeutral.evaluate(now);
                if(result!=null && "strategy.STR-209".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-209");
            }
        }

        if(portfolioOverlay!=null){
            try{
                var result=portfolioOverlay.evaluate(now);
                if(result!=null && "strategy.STR-210".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-210");
            }
        }

        if(greeksRebalance!=null){
            try{
                var result=greeksRebalance.evaluate(now);
                if(result!=null && "strategy.STR-211".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-211");
            }
        }

        if(skewHedge!=null){
            try{
                var result=skewHedge.evaluate(now);
                if(result!=null && "strategy.STR-212".equals(result.strategyId())
                        && result.status()!=null && result.reasonCode()!=null){
                    if(observed.putIfAbsent(result.strategyId(),result)!=null)
                        observed.remove(result.strategyId());
                }
            }catch(RuntimeException unavailable){
                observed.remove("strategy.STR-212");
            }
        }

        List<StrategyRow> rows = new ArrayList<>(definitions.size());
        for (var definition : definitions) {
            var runtime = observed.get(definition.id());
            String state;
            String reason;
            if (DIRECT.contains(definition.id())) {
                state = runtime == null ? "DIRECT_ADAPTER_UNAVAILABLE" : "DIRECT_EVALUATOR_OBSERVED";
                reason = runtime == null ? "RUNTIME_EVALUATION_EVIDENCE_MISSING" : runtime.reasonCode();
            } else if (SIGNAL_ONLY.contains(definition.id())) {
                state = "SIGNAL_ROUTE_ONLY";
                reason = "DISTINCT_EVALUATOR_NOT_INVOKED";
            } else {
                state = "NO_TYPED_SOURCE_ADAPTER";
                reason = "DIRECT_EVALUATOR_ADAPTER_NOT_CONNECTED";
            }
            rows.add(new StrategyRow(
                definition.id(), definition.name(), definition.source(), definition.requiredInputs(),
                state, runtime == null ? "NOT_EVALUATED" : runtime.status(), reason,
                runtime == null ? null : runtime.evaluatedAt(),
                runtime == null ? "NONE" : runtime.quoteSource(),
                runtime == null ? 0 : runtime.contractsResolved(),
                runtime == null ? 0 : runtime.freshQuotes(),
                runtime == null ? 0 : runtime.historySessions(),
                runtime != null,
                false, false, false, false));
        }
        return new Coverage(
            "minttick.fno43.runtime-source-readiness.v1", now, definitions.size(),
            observed.size(), SIGNAL_ONLY.size(), definitions.size() - DIRECT.size(),
            0, 0, 0, 0, List.copyOf(rows));
    }

    private static List<Definition> readManifest(ObjectMapper mapper) {
        TreeMap<Integer, Definition> selected = new TreeMap<>();
        try (InputStream input = Fno43StrategyReadinessService.class.getResourceAsStream(MANIFEST)) {
            if (input == null) throw new IllegalStateException("FNO43_MANIFEST_MISSING");
            JsonNode entries = mapper.readTree(input).path("items");
            if (!entries.isArray()) throw new IllegalStateException("FNO43_MANIFEST_ITEMS_INVALID");
            for (JsonNode item : entries) {
                String id = item.path("id").asText("");
                if (!id.matches("strategy\\.STR-\\d{3}")) continue;
                int numericId = Integer.parseInt(id.substring(id.length() - 3));
                if (numericId < 170 || numericId > 212) continue;
                if (!"DATA_BLOCKED".equals(item.path("status").asText())
                        || !"JAVA_DETERMINISTIC".equals(
                                item.path("implementation").path("runtime").asText())) {
                    throw new IllegalStateException("FNO43_UNREVIEWED_STRATEGY_STATUS");
                }
                List<String> inputs = new ArrayList<>();
                for (JsonNode required : item.path("requiredInputs")) {
                    if (required.isTextual() && !required.asText().isBlank()) {
                        inputs.add(required.asText());
                    }
                }
                String source = "";
                for (JsonNode proof : item.path("evidence")) {
                    if ("SOURCE".equals(proof.path("kind").asText())) {
                        source = proof.path("path").asText("");
                        break;
                    }
                }
                String name = item.path("name").asText("");
                if (inputs.isEmpty() || name.isBlank() || !source.startsWith(
                        "backend/src/main/java/in/aitradingsystem/tradingrooms/Fno")) {
                    throw new IllegalStateException("FNO43_STRATEGY_INPUT_CONTRACT_INCOMPLETE");
                }
                if (selected.putIfAbsent(numericId,
                        new Definition(id, name, source, List.copyOf(inputs))) != null) {
                    throw new IllegalStateException("FNO43_DUPLICATE_STRATEGY");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("FNO43_MANIFEST_UNREADABLE", e);
        }
        if (selected.size() != 43) throw new IllegalStateException("FNO43_MANIFEST_NOT_43");
        for (int n = 170; n <= 212; n++) {
            if (!selected.containsKey(n)) throw new IllegalStateException("FNO43_MANIFEST_GAP");
        }
        return List.copyOf(selected.values());
    }
}
