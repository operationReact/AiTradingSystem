package in.aitradingsystem.tradingrooms;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * STR-178 — read-only index-futures portfolio hedge feasibility research.
 *
 * <p>Estimates the residual of a verified, signed portfolio index-beta exposure
 * after an integer number of index future lots. It requires synchronized,
 * licensed bid/ask and displayed depth, a current portfolio including existing
 * derivatives, validated broker margin, and explicit shock/basis/cost stress.
 * No price prediction, broker order or assumed execution is produced.
 *
 * <p>Unlike directional futures alpha strategies, this asks whether adding a
 * portfolio hedge would reduce an explicitly modeled stress loss. A qualifying
 * result is only a hypothetical research candidate, not a risk-limit override.
 */
public final class FnoFuturesHedgeOverlayV1 {
    public static final String STRATEGY_ID = "strategy.STR-178";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final BigDecimal MAX_MONEY = new BigDecimal("1000000000000");
    private static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(15);
    private static final Duration MAX_PORTFOLIO_AGE = Duration.ofMinutes(5);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private FnoFuturesHedgeOverlayV1() {}

    public enum Status {
        BLOCKED_RIGHTS, BLOCKED_DATA, INFEASIBLE, NO_HEDGE, RESEARCH_CANDIDATE
    }
    public enum ProposedSide { NONE, SHORT_INDEX_FUTURE, LONG_INDEX_FUTURE }

    /** Verified NSE index future. Stock futures require a different hedge model. */
    public record Contract(String instrumentKey, String underlyingIndexKey,
                           LocalDate expiry, int lotSize, boolean providerVerified) {}

    /**
     * Signed beta-adjusted INR exposure *to this exact underlying index*.
     * An upstream risk engine must incorporate both current cash holdings
     * and existing futures/options positions; it must certify its beta model.
     */
    public record Portfolio(String indexUnderlyingKey, BigDecimal signedBetaExposureInr,
                            Instant exposureCalculatedAt, Instant firstObservedAt,
                            boolean positionsReconciled, boolean existingHedgesIncluded,
                            boolean betaModelVerified) {}

    /** Actual source-observed L1 bid/ask and OI, depth in underlying units. */
    public record MarketQuote(String instrumentKey, String provider,
                              BigDecimal bid, BigDecimal ask,
                              long bidDepthUnits, long askDepthUnits, long openInterestUnits,
                              Instant exchangeAt, Instant observedAt,
                              boolean sessionVerified, boolean quoteProvenanceVerified) {}

    /** Separate internal research rights; never implies live order or display rights. */
    public record SourceRights(boolean futuresResearchVerified,
                               boolean contractsVerified, boolean portfolioResearchVerified) {}

    /** Independently verified free cash and current broker hedge margin scenario. */
    public record Funding(BigDecimal availableCashInr, BigDecimal initialMarginPerLotInr,
                          BigDecimal variationStressReservePerLotInr,
                          boolean balanceVerified, boolean brokerMarginVerified) {}

    /**
     * Explicit stress shock, per-lot basis divergence and all-in round-trip cost
     * in INR. No cost/risk calibration is inferred from account permissions.
     */
    public record Stress(int indexShockBps, BigDecimal basisStressPerLotInr,
                         BigDecimal allInRoundTripCostPerLotInr, int maxHedgeLots,
                         int maxBidAskSpreadBps, int minimumRiskReductionBps,
                         boolean scenarioReviewed, boolean costsReviewed) {}

    public record Decision(Status status, ProposedSide proposedSide, String reasonCode,
                           String strategyId, Instant asOf, String instrumentKey,
                           String provider, int hypotheticalLots,
                           BigDecimal currentSignedBetaExposureInr,
                           BigDecimal residualSignedBetaExposureInr,
                           BigDecimal requiredFundingInr, BigDecimal riskBeforeInr,
                           BigDecimal stressedRiskAfterInr, BigDecimal riskReductionBps) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    /**
     * Search at most maxHedgeLots (<=100) whole lots and choose the greatest
     * post-cost stress reduction without reversing the portfolio beta exposure.
     * Only a separate authorized execution/risk stack could ever place a hedge.
     */
    public static Decision evaluate(Contract future, Portfolio portfolio,
                                    MarketQuote quote, SourceRights rights,
                                    Funding funding, Stress stress, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.futuresResearchVerified()
                || !rights.contractsVerified() || !rights.portfolioResearchVerified()) {
            return blocked(Status.BLOCKED_RIGHTS, "HEDGE_RESEARCH_RIGHTS_NOT_VERIFIED", asOf);
        }
        if (!validContract(future, asOf) || !validPortfolio(portfolio, asOf)) {
            return blocked(Status.BLOCKED_DATA, "HEDGE_CONTRACT_OR_PORTFOLIO_INVALID", asOf);
        }
        if (!future.underlyingIndexKey().equals(portfolio.indexUnderlyingKey())) {
            return blocked(Status.BLOCKED_DATA, "HEDGE_PORTFOLIO_INDEX_MISMATCH", asOf);
        }
        if (!validQuote(quote, future.instrumentKey(), asOf)) {
            return blocked(Status.BLOCKED_DATA, "HEDGE_MARKET_QUOTE_INVALID_OR_STALE", asOf);
        }
        if (!validFunding(funding) || !validStress(stress)) {
            return blocked(Status.BLOCKED_DATA, "HEDGE_MARGIN_OR_COST_PROOF_MISSING", asOf);
        }
        BigDecimal signedExposure = portfolio.signedBetaExposureInr();
        if (signedExposure.signum() == 0) {
            return noHedge("HEDGE_PORTFOLIO_ALREADY_INDEX_NEUTRAL", future, quote,
                signedExposure, asOf);
        }
        boolean sellFuture = signedExposure.signum() > 0;
        BigDecimal executableSidePrice = sellFuture ? quote.bid() : quote.ask();
        long liquidityUnits = sellFuture ? quote.bidDepthUnits() : quote.askDepthUnits();
        BigDecimal mid = quote.bid().add(quote.ask(), MC).divide(BigDecimal.valueOf(2), MC);
        BigDecimal spreadBps = quote.ask().subtract(quote.bid(), MC)
            .multiply(BPS, MC).divide(mid, MC);
        if (spreadBps.compareTo(BigDecimal.valueOf(stress.maxBidAskSpreadBps())) > 0) {
            return noHedge("HEDGE_SPREAD_EXCEEDS_RESEARCH_LIMIT", future, quote,
                signedExposure, asOf);
        }
        BigDecimal oneLotNotional = executableSidePrice.multiply(
            BigDecimal.valueOf(future.lotSize()), MC);
        BigDecimal absoluteExposure = signedExposure.abs();
        if (oneLotNotional.signum() <= 0 || oneLotNotional.compareTo(MAX_MONEY) > 0) {
            return blocked(Status.BLOCKED_DATA, "HEDGE_FUTURE_NOTIONAL_INVALID", asOf);
        }
        if (oneLotNotional.compareTo(absoluteExposure) > 0) {
            return noHedge("HEDGE_ONE_LOT_WOULD_OVERHEDGE", future, quote,
                signedExposure, asOf);
        }

        BigDecimal fundingPerLot = funding.initialMarginPerLotInr()
            .add(funding.variationStressReservePerLotInr(), MC);
        BigDecimal riskPerLot = stress.basisStressPerLotInr()
            .add(stress.allInRoundTripCostPerLotInr(), MC);
        BigDecimal before = absoluteExposure.multiply(
            BigDecimal.valueOf(stress.indexShockBps()), MC).divide(BPS, MC);
        int bestLots = 0;
        BigDecimal bestRisk = before;
        BigDecimal bestResidual = signedExposure;
        BigDecimal bestFunding = BigDecimal.ZERO;
        boolean atLeastOneAffordable = false;
        boolean atLeastOneLiquid = liquidityUnits >= future.lotSize();

        for (int lots = 1; lots <= stress.maxHedgeLots(); lots++) {
            long underlyingUnits = (long) lots * future.lotSize();
            if (underlyingUnits > liquidityUnits) break;
            BigDecimal n = BigDecimal.valueOf(lots);
            BigDecimal hedgeNotional = oneLotNotional.multiply(n, MC);
            if (hedgeNotional.compareTo(absoluteExposure) > 0) break;
            BigDecimal required = fundingPerLot.multiply(n, MC);
            if (required.compareTo(funding.availableCashInr()) > 0) break;
            atLeastOneAffordable = true;

            BigDecimal residual = sellFuture
                ? signedExposure.subtract(hedgeNotional, MC)
                : signedExposure.add(hedgeNotional, MC);
            BigDecimal after = residual.abs().multiply(
                BigDecimal.valueOf(stress.indexShockBps()), MC).divide(BPS, MC)
                .add(riskPerLot.multiply(n, MC), MC);
            if (after.compareTo(bestRisk) < 0) {
                bestRisk = after;
                bestLots = lots;
                bestResidual = residual;
                bestFunding = required;
            }
        }

        if (!atLeastOneLiquid) {
            return new Decision(Status.INFEASIBLE, ProposedSide.NONE,
                "HEDGE_ONE_LOT_MARKET_DEPTH_INSUFFICIENT", STRATEGY_ID, asOf,
                future.instrumentKey(), quote.provider(), 0, signedExposure,
                signedExposure, null, money(before), null, null);
        }
        if (!atLeastOneAffordable) {
            return new Decision(Status.INFEASIBLE, ProposedSide.NONE,
                "HEDGE_AVAILABLE_MARGIN_INSUFFICIENT", STRATEGY_ID, asOf,
                future.instrumentKey(), quote.provider(), 0, signedExposure,
                signedExposure, fundingPerLot, money(before), null, null);
        }
        if (bestLots == 0) {
            return noHedge("HEDGE_STRESSED_RISK_NOT_IMPROVED", future, quote,
                signedExposure, asOf);
        }

        BigDecimal reduction = before.subtract(bestRisk, MC)
            .multiply(BPS, MC).divide(before, MC);
        if (reduction.compareTo(BigDecimal.valueOf(
                stress.minimumRiskReductionBps())) < 0) {
            return new Decision(Status.NO_HEDGE, ProposedSide.NONE,
                "HEDGE_RISK_REDUCTION_BELOW_HURDLE", STRATEGY_ID, asOf,
                future.instrumentKey(), quote.provider(), 0, signedExposure,
                signedExposure, null, money(before), null, round(reduction));
        }
        return new Decision(Status.RESEARCH_CANDIDATE,
            sellFuture ? ProposedSide.SHORT_INDEX_FUTURE : ProposedSide.LONG_INDEX_FUTURE,
            "HEDGE_STRESSED_EXPOSURE_REDUCTION_RESEARCH_ONLY", STRATEGY_ID, asOf,
            future.instrumentKey(), quote.provider(), bestLots, signedExposure,
            money(bestResidual), money(bestFunding), money(before),
            money(bestRisk), round(reduction));
    }

    private static boolean validContract(Contract c, Instant asOf) {
        if (c == null || !c.providerVerified() || c.instrumentKey() == null
                || !c.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || c.underlyingIndexKey() == null
                || !c.underlyingIndexKey().matches("NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64}")
                || c.expiry() == null || c.lotSize() < 1 || c.lotSize() > 100000)
            return false;
        long untilExpiry = ChronoUnit.DAYS.between(
            asOf.atZone(IST).toLocalDate(), c.expiry());
        return untilExpiry >= 3 && untilExpiry <= 100;
    }

    private static boolean validPortfolio(Portfolio p, Instant asOf) {
        return p != null && p.positionsReconciled() && p.existingHedgesIncluded()
            && p.betaModelVerified() && p.indexUnderlyingKey() != null
            && p.exposureCalculatedAt() != null && p.firstObservedAt() != null
            && p.signedBetaExposureInr() != null
            && p.signedBetaExposureInr().abs().compareTo(MAX_MONEY) <= 0
            && !p.exposureCalculatedAt().isAfter(p.firstObservedAt())
            && !p.firstObservedAt().isAfter(asOf)
            && Duration.between(p.exposureCalculatedAt(), asOf)
                .compareTo(MAX_PORTFOLIO_AGE) <= 0
            && Duration.between(p.firstObservedAt(), asOf)
                .compareTo(MAX_PORTFOLIO_AGE) <= 0;
    }

    private static boolean validQuote(MarketQuote q, String instrumentKey, Instant asOf) {
        return q != null && q.sessionVerified() && q.quoteProvenanceVerified()
            && instrumentKey.equals(q.instrumentKey())
            && ("UPSTOX_V3".equals(q.provider())
                || "ANGEL_ONE_SMARTAPI".equals(q.provider()))
            && positive(q.bid()) && positive(q.ask())
            && q.ask().compareTo(q.bid()) >= 0
            && q.bidDepthUnits() >= 0 && q.askDepthUnits() >= 0
            && q.bidDepthUnits() <= 1_000_000_000L
            && q.askDepthUnits() <= 1_000_000_000L
            && q.openInterestUnits() > 0 && q.openInterestUnits() <= 1_000_000_000_000L
            && q.exchangeAt() != null && q.observedAt() != null
            && !q.exchangeAt().isAfter(q.observedAt()) && !q.observedAt().isAfter(asOf)
            && Duration.between(q.exchangeAt(), asOf).compareTo(MAX_QUOTE_AGE) <= 0;
    }

    private static boolean validFunding(Funding f) {
        return f != null && f.balanceVerified() && f.brokerMarginVerified()
            && moneyRange(f.availableCashInr(), false)
            && moneyRange(f.initialMarginPerLotInr(), true)
            && moneyRange(f.variationStressReservePerLotInr(), true);
    }

    private static boolean validStress(Stress s) {
        return s != null && s.scenarioReviewed() && s.costsReviewed()
            && s.indexShockBps() >= 100 && s.indexShockBps() <= 5000
            && moneyRange(s.basisStressPerLotInr(), true)
            && moneyRange(s.allInRoundTripCostPerLotInr(), true)
            && s.maxHedgeLots() >= 1 && s.maxHedgeLots() <= 100
            && s.maxBidAskSpreadBps() >= 1 && s.maxBidAskSpreadBps() <= 1000
            && s.minimumRiskReductionBps() >= 1 && s.minimumRiskReductionBps() <= 9900;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean moneyRange(BigDecimal value, boolean strictlyPositive) {
        return value != null && (strictlyPositive ? value.signum() > 0 : value.signum() >= 0)
            && value.compareTo(MAX_MONEY) <= 0;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
    private static BigDecimal round(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static Decision noHedge(String reason, Contract future, MarketQuote quote,
                                    BigDecimal exposure, Instant asOf) {
        return new Decision(Status.NO_HEDGE, ProposedSide.NONE, reason,
            STRATEGY_ID, asOf, future.instrumentKey(), quote.provider(), 0,
            exposure, exposure, null, null, null, null);
    }
    private static Decision blocked(Status status, String reason, Instant asOf) {
        return new Decision(status, ProposedSide.NONE, reason, STRATEGY_ID, asOf,
            null, null, 0, null, null, null, null, null, null);
    }
}
