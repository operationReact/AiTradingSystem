package in.aitradingsystem.tradingrooms;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * STR-205 (36/43) -- research-only local portfolio-beta hedge with LONG
 * source-listed NIFTY index options. Uses broker-confirmed long equity holdings,
 * source-original historical closes and option delta. All outputs are
 * hypothetical; this is neither historical trade performance nor an order.
 *
 * A current-weight return-series beta is NOT a forecast or a guaranteed hedge.
 * The beta is calculated using only completed, original-first-known observations.
 */
public final class FnoBetaHedgeOptionsV1 {
    public static final String STRATEGY_ID = "strategy.STR-205";
    public static final String INDEX = FnoLongOptionCrossoverV1.UNDERLYING;
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10000);
    private static final BigDecimal MIN_INDEX_VARIANCE = new BigDecimal("0.00000001");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime CLOSE = LocalTime.of(15,30);
    private static final LocalTime OPEN = LocalTime.of(9,15);

    private FnoBetaHedgeOptionsV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_HEDGE, RESEARCH_CANDIDATE }

    public record Rights(boolean brokerHoldingsResearch, boolean indexHistoryResearch,
                         boolean equityHistoryResearch, boolean originalOptionGreeksResearch,
                         boolean optionChainResearch, boolean instrumentMasterResearch) {}
    /** Verified, positive, owned equity shares: shorts and derivatives unsupported. */
    public record Holding(String key, long shares, Instant brokerFirstKnownAt,
                          boolean brokerConfirmed) {}
    public record EquityQuote(String key, String provider, BigDecimal lastPriceInr,
                              Instant exchangeAt, Instant firstKnownAt,
                              boolean sourceVerified) {}
    public record IndexClose(LocalDate session, String provider, BigDecimal close,
                             Instant exchangeCloseAt, Instant firstKnownAt,
                             boolean sourceVerified) {}
    public record EquityClose(String key, String provider, BigDecimal close,
                              Instant exchangeCloseAt, Instant firstKnownAt,
                              boolean sourceVerified) {}
    public record Session(IndexClose index, List<EquityClose> equities) {}
    public record OptionContract(String key, String index, String type,
                                 BigDecimal strike, BigDecimal tick,
                                 LocalDate listedFrom, LocalDate expiry,
                                 int officialLotSize, boolean sourceListed) {}
    /** Original independently supplied Greek delta is per underlying unit. */
    public record OptionQuote(String key, String provider, BigDecimal bid,
                              BigDecimal ask, BigDecimal originalDelta,
                              BigDecimal originalAnnualizedIvPercent, long openInterestLots,
                              long askDepthLots, Instant exchangeAt,
                              Instant firstKnownAt, boolean sourceVerified) {}
    public record Snapshot(String provider, String indexKey, BigDecimal indexSpot,
                           List<EquityQuote> equityQuotes, OptionQuote option,
                           Instant exchangeAt, Instant firstKnownAt,
                           boolean sourceVerified) {}
    public record Policy(int minCompletedSessions, int maxQuoteAgeSeconds,
                         int maxQuoteSkewSeconds, int maxOptionSpreadBps,
                         long minOptionOiLots, int maxAnnualizedIvPercent,
                         int maxBuyLots, int buySlippageBps, int maxPremiumBudgetBps,
                         int minimumRiskReductionBps, BigDecimal minAbsoluteBeta,
                         BigDecimal maxAbsoluteBeta, BigDecimal feeInr) {}
    public record Decision(Status status, String reasonCode, String strategyId,
                           Instant asOf, int completedReturnObservations,
                           BigDecimal observedPortfolioBeta, BigDecimal portfolioValueInr,
                           BigDecimal observedBetaExposureInr,
                           String hypotheticalLongOptionType, int hypotheticalBuyLots,
                           BigDecimal hypotheticalOptionDeltaExposureInr,
                           BigDecimal hypotheticalResidualBetaExposureInr,
                           BigDecimal hypotheticalPurchaseCostInr,
                           boolean currentWeightHistoricalProxyOnly) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean paperTradeAuthorized() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(List<Holding> brokerHoldings,
                                    OptionContract contract, Snapshot snapshot,
                                    List<Session> originalCompletedSessions,
                                    Rights rights, Policy policy, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.brokerHoldingsResearch()
                || !rights.indexHistoryResearch() || !rights.equityHistoryResearch()
                || !rights.originalOptionGreeksResearch() || !rights.optionChainResearch()
                || !rights.instrumentMasterResearch())
            return blocked(Status.BLOCKED_RIGHTS, "BETA_HEDGE_SOURCE_RIGHTS_UNVERIFIED", asOf);

        if (!validPolicy(policy) || !validContract(contract, asOf)
                || !validSnapshot(snapshot, contract, policy, asOf))
            return blocked(Status.BLOCKED_DATA, "BETA_HEDGE_OPTION_OR_SOURCE_INVALID", asOf);

        Map<String, BigDecimal> values = new HashMap<>();
        if (!validHoldingsAndQuotes(brokerHoldings, snapshot, policy, asOf, values))
            return blocked(Status.BLOCKED_DATA, "BETA_HEDGE_HOLDINGS_OR_EQUITY_QUOTES_INVALID", asOf);

        if (!validHistory(originalCompletedSessions, values.keySet(),
                snapshot, policy, asOf))
            return blocked(Status.BLOCKED_DATA, "BETA_HEDGE_ORIGINAL_HISTORY_INVALID", asOf);

        BigDecimal portfolioValue = BigDecimal.ZERO;
        for (BigDecimal v : values.values()) portfolioValue = portfolioValue.add(v, MC);
        if (portfolioValue.signum() <= 0)
            return blocked(Status.BLOCKED_DATA, "BETA_HEDGE_PORTFOLIO_VALUE_INVALID", asOf);

        Map<String, BigDecimal> weights = new HashMap<>();
        for (var entry : values.entrySet())
            weights.put(entry.getKey(), entry.getValue().divide(portfolioValue, MC));

        // Original daily observations only, no future-known candles. The fixed
        // current holdings weights yield a research return proxy, not actual
        // historic holdings/rebalancing results.
        BigDecimal sumIndex = BigDecimal.ZERO;
        BigDecimal sumPortfolio = BigDecimal.ZERO;
        BigDecimal sumIndexSquared = BigDecimal.ZERO;
        BigDecimal sumCross = BigDecimal.ZERO;
        for (int t = 1; t < originalCompletedSessions.size(); t++) {
            Session previous = originalCompletedSessions.get(t-1);
            Session current = originalCompletedSessions.get(t);
            BigDecimal indexReturn = current.index().close()
                    .divide(previous.index().close(), MC).subtract(BigDecimal.ONE, MC);
            BigDecimal portfolioReturn = BigDecimal.ZERO;
            Map<String, BigDecimal> previousCloses = closeMap(previous.equities());
            for (EquityClose eq : current.equities()) {
                BigDecimal securityReturn = eq.close()
                        .divide(previousCloses.get(eq.key()), MC)
                        .subtract(BigDecimal.ONE, MC);
                portfolioReturn = portfolioReturn.add(
                        weights.get(eq.key()).multiply(securityReturn, MC), MC);
            }
            sumIndex = sumIndex.add(indexReturn, MC);
            sumPortfolio = sumPortfolio.add(portfolioReturn, MC);
            sumIndexSquared = sumIndexSquared.add(indexReturn.multiply(indexReturn, MC), MC);
            sumCross = sumCross.add(indexReturn.multiply(portfolioReturn, MC), MC);
        }

        int observedReturns = originalCompletedSessions.size()-1;
        BigDecimal count = BigDecimal.valueOf(observedReturns);
        BigDecimal indexVar = sumIndexSquared.divide(count, MC)
                .subtract(sumIndex.divide(count, MC).pow(2, MC), MC);
        if (indexVar.compareTo(MIN_INDEX_VARIANCE) <= 0)
            return blocked(Status.BLOCKED_DATA, "BETA_HEDGE_INDEX_VARIANCE_INSUFFICIENT", asOf);
        BigDecimal covariance = sumCross.divide(count, MC).subtract(
                sumIndex.divide(count, MC).multiply(sumPortfolio.divide(count, MC), MC), MC);
        BigDecimal beta = covariance.divide(indexVar, MC);
        if (beta.abs().compareTo(policy.maxAbsoluteBeta()) > 0)
            return blocked(Status.BLOCKED_DATA, "BETA_HEDGE_UNSTABLE_BETA_OUT_OF_BOUNDS", asOf);

        BigDecimal exposure = portfolioValue.multiply(beta, MC);
        if (beta.abs().compareTo(policy.minAbsoluteBeta()) < 0)
            return measured(Status.NO_HEDGE, "BETA_HEDGE_OBSERVED_BETA_TOO_SMALL",
                    asOf, observedReturns, beta, portfolioValue, exposure,
                    null, 0, BigDecimal.ZERO, exposure, BigDecimal.ZERO);

        OptionQuote quote = snapshot.option();
        if (!("PE".equals(contract.type()) && exposure.signum() > 0)
                && !("CE".equals(contract.type()) && exposure.signum() < 0))
            return measured(Status.NO_HEDGE, "BETA_HEDGE_WRONG_LONG_OPTION_DIRECTION",
                    asOf, observedReturns, beta, portfolioValue, exposure,
                    null, 0, BigDecimal.ZERO, exposure, BigDecimal.ZERO);

        BigDecimal oneLotExposure = quote.originalDelta()
                .multiply(BigDecimal.valueOf(contract.officialLotSize()), MC)
                .multiply(snapshot.indexSpot(), MC);
        // Monotonic risk improvement only: a long put offsets positive beta;
        // a long call offsets negative beta. Never short naked options.
        int availableLots = (int)Math.min((long)policy.maxBuyLots(), quote.askDepthLots());
        BigDecimal bestResidual = exposure;
        int bestLots = 0;
        for (int lots = 1; lots <= availableLots; lots++) {
            BigDecimal residual = exposure.add(
                    oneLotExposure.multiply(BigDecimal.valueOf(lots), MC), MC);
            if (residual.abs().compareTo(bestResidual.abs()) < 0) {
                bestLots = lots;
                bestResidual = residual;
            }
        }
        if (bestLots == 0)
            return measured(Status.NO_HEDGE, "BETA_HEDGE_LOTS_DO_NOT_REDUCE_EXPOSURE",
                    asOf, observedReturns, beta, portfolioValue, exposure,
                    null, 0, BigDecimal.ZERO, exposure, BigDecimal.ZERO);

        BigDecimal reductionBps = exposure.abs().subtract(bestResidual.abs(), MC)
                .multiply(TEN_THOUSAND, MC).divide(exposure.abs(), MC);
        if (reductionBps.compareTo(BigDecimal.valueOf(policy.minimumRiskReductionBps())) < 0)
            return measured(Status.NO_HEDGE, "BETA_HEDGE_REDUCTION_TOO_SMALL",
                    asOf, observedReturns, beta, portfolioValue, exposure,
                    null, 0, BigDecimal.ZERO, exposure, BigDecimal.ZERO);

        BigDecimal quantity = BigDecimal.valueOf((long) bestLots * contract.officialLotSize());
        BigDecimal effectiveAsk = quote.ask().multiply(
                TEN_THOUSAND.add(BigDecimal.valueOf(policy.buySlippageBps()), MC)
                        .divide(TEN_THOUSAND, MC), MC);
        BigDecimal purchaseCost = effectiveAsk.multiply(quantity, MC)
                .add(policy.feeInr(), MC);
        BigDecimal budgetBps = purchaseCost.multiply(TEN_THOUSAND, MC)
                .divide(portfolioValue, MC);
        if (budgetBps.compareTo(BigDecimal.valueOf(policy.maxPremiumBudgetBps())) > 0)
            return measured(Status.NO_HEDGE, "BETA_HEDGE_PREMIUM_BUDGET_EXCEEDED",
                    asOf, observedReturns, beta, portfolioValue, exposure,
                    null, 0, BigDecimal.ZERO, exposure, purchaseCost);

        BigDecimal hedgeExposure = oneLotExposure.multiply(BigDecimal.valueOf(bestLots), MC);
        return measured(Status.RESEARCH_CANDIDATE, "BETA_HEDGE_HYPOTHETICAL_LONG_OPTION_ONLY",
                asOf, observedReturns, beta, portfolioValue, exposure,
                contract.type(), bestLots, hedgeExposure, bestResidual, purchaseCost);
    }

    private static Map<String,BigDecimal> closeMap(List<EquityClose> equities) {
        Map<String,BigDecimal> map = new HashMap<>();
        for (EquityClose e : equities) map.put(e.key(), e.close());
        return map;
    }

    private static boolean validHoldingsAndQuotes(List<Holding> holdings, Snapshot s,
                                                  Policy p, Instant at,
                                                  Map<String,BigDecimal> values) {
        if (holdings == null || holdings.isEmpty() || holdings.size() > 12
                || s.equityQuotes() == null || s.equityQuotes().size() != holdings.size())
            return false;
        Set<String> holdingsSeen = new HashSet<>();
        for (Holding h : holdings) {
            if (h == null || !equityKey(h.key()) || h.shares() <= 0
                    || h.shares() > 10_000_000L || !h.brokerConfirmed()
                    || h.brokerFirstKnownAt() == null
                    || h.brokerFirstKnownAt().isAfter(s.exchangeAt())
                    || !holdingsSeen.add(h.key())) return false;
        }
        Set<String> quotesSeen = new HashSet<>();
        for (EquityQuote q : s.equityQuotes()) {
            if (q == null || !q.sourceVerified() || !holdingsSeen.contains(q.key())
                    || !quotesSeen.add(q.key()) || !s.provider().equals(q.provider())
                    || !positive(q.lastPriceInr()) || q.exchangeAt() == null
                    || q.firstKnownAt() == null
                    || q.firstKnownAt().isBefore(q.exchangeAt())
                    || q.firstKnownAt().isAfter(at)
                    || Duration.between(q.exchangeAt(), at).compareTo(
                            Duration.ofSeconds(p.maxQuoteAgeSeconds())) > 0
                    || Math.abs(Duration.between(q.exchangeAt(), s.exchangeAt()).toSeconds())
                            > p.maxQuoteSkewSeconds()) return false;
        }
        Map<String, Long> shares = new HashMap<>();
        for (Holding h : holdings) shares.put(h.key(),h.shares());
        for (EquityQuote q : s.equityQuotes()) {
            BigDecimal notional=q.lastPriceInr().multiply(
                    BigDecimal.valueOf(shares.get(q.key())), MC);
            if (notional.signum()<=0) return false;
            values.put(q.key(),notional);
        }
        return values.size() == holdings.size();
    }

    private static boolean validHistory(List<Session> history, Set<String> keys,
                                        Snapshot snapshot, Policy p, Instant at) {
        if (history == null || history.size() < p.minCompletedSessions()
                || history.size() > 300) return false;
        LocalDate previous=null;
        for (Session session : history) {
            if (session == null || session.index() == null) return false;
            IndexClose index=session.index();
            if (index.session()==null || index.session().getDayOfWeek().getValue()>5
                    || !snapshot.provider().equals(index.provider()) || !index.sourceVerified()
                    || !positive(index.close()) || index.exchangeCloseAt()==null
                    || index.firstKnownAt()==null) return false;
            Instant close=index.session().atTime(CLOSE).atZone(IST).toInstant();
            if (!close.equals(index.exchangeCloseAt())
                    || index.firstKnownAt().isBefore(close)
                    || index.firstKnownAt().isAfter(close.plus(Duration.ofHours(36)))
                    || !index.firstKnownAt().isBefore(snapshot.exchangeAt())
                    || index.firstKnownAt().isAfter(at)
                    || (previous != null && (ChronoUnit.DAYS.between(previous,index.session())<1
                    || ChronoUnit.DAYS.between(previous,index.session())>8)))
                return false;
            if (session.equities() == null || session.equities().size()!=keys.size()) return false;
            Set<String> observed = new HashSet<>();
            for (EquityClose e : session.equities()) {
                if (e==null || !keys.contains(e.key()) || !observed.add(e.key())
                        || !snapshot.provider().equals(e.provider()) || !e.sourceVerified()
                        || !positive(e.close()) || e.exchangeCloseAt()==null
                        || !e.exchangeCloseAt().equals(close) || e.firstKnownAt()==null
                        || e.firstKnownAt().isBefore(close)
                        || e.firstKnownAt().isAfter(close.plus(Duration.ofHours(36)))
                        || !e.firstKnownAt().isBefore(snapshot.exchangeAt())
                        || e.firstKnownAt().isAfter(at))
                    return false;
            }
            previous=index.session();
        }
        LocalDate snapshotDate=snapshot.exchangeAt().atZone(IST).toLocalDate();
        long lag=ChronoUnit.DAYS.between(previous,snapshotDate);
        return lag>=1 && lag<=5;
    }

    private static boolean validPolicy(Policy p) {
        return p!=null && between(p.minCompletedSessions(),61,250)
                && between(p.maxQuoteAgeSeconds(),30,600)
                && between(p.maxQuoteSkewSeconds(),0,120)
                && between(p.maxOptionSpreadBps(),1,5000)
                && p.minOptionOiLots()>=1
                && between(p.maxAnnualizedIvPercent(),30,500)
                && between(p.maxBuyLots(),1,500)
                && between(p.buySlippageBps(),0,1000)
                && between(p.maxPremiumBudgetBps(),1,5000)
                && between(p.minimumRiskReductionBps(),1,9900)
                && positive(p.minAbsoluteBeta())
                && positive(p.maxAbsoluteBeta())
                && p.maxAbsoluteBeta().compareTo(p.minAbsoluteBeta())>=0
                && positive(p.feeInr());
    }

    private static boolean validContract(OptionContract c, Instant asOf) {
        if(c==null || !c.sourceListed() || !optionKey(c.key()) || !INDEX.equals(c.index())
                || !("PE".equals(c.type()) || "CE".equals(c.type()))
                || !positive(c.strike()) || !positive(c.tick()) || c.officialLotSize()<1
                || c.strike().remainder(c.tick()).signum()!=0 || c.listedFrom()==null
                || c.expiry()==null) return false;
        LocalDate session=asOf.atZone(IST).toLocalDate();
        long dte=ChronoUnit.DAYS.between(session,c.expiry());
        return !c.listedFrom().isAfter(session) && dte>=3 && dte<=45;
    }
    private static boolean validSnapshot(Snapshot s, OptionContract c,
                                         Policy p, Instant at) {
        if (s==null || !s.sourceVerified() || !provider(s.provider())
                || !INDEX.equals(s.indexKey()) || !positive(s.indexSpot())
                || s.exchangeAt()==null || s.firstKnownAt()==null
                || s.firstKnownAt().isBefore(s.exchangeAt())
                || s.firstKnownAt().isAfter(at)
                || Duration.between(s.exchangeAt(),at).compareTo(
                        Duration.ofSeconds(p.maxQuoteAgeSeconds()))>0)
            return false;
        var z=s.exchangeAt().atZone(IST);
        if(z.getDayOfWeek().getValue()>5 || z.toLocalTime().isBefore(OPEN)
                || z.toLocalTime().isAfter(CLOSE)) return false;
        OptionQuote q=s.option();
        if(q==null || !q.sourceVerified() || !c.key().equals(q.key())
                || !s.provider().equals(q.provider()) || !positive(q.bid())
                || !positive(q.ask()) || q.ask().compareTo(q.bid())<0
                || !validIv(q.originalAnnualizedIvPercent(),p.maxAnnualizedIvPercent())
                || q.originalDelta()==null || q.openInterestLots()<p.minOptionOiLots()
                || q.askDepthLots()<1 || q.exchangeAt()==null || q.firstKnownAt()==null
                || q.firstKnownAt().isBefore(q.exchangeAt())
                || q.firstKnownAt().isAfter(at)
                || Duration.between(q.exchangeAt(),at).compareTo(
                        Duration.ofSeconds(p.maxQuoteAgeSeconds()))>0
                || Math.abs(Duration.between(q.exchangeAt(),s.exchangeAt()).toSeconds())
                        > p.maxQuoteSkewSeconds()) return false;
        BigDecimal delta=q.originalDelta();
        if ("CE".equals(c.type())
                ? delta.signum()<=0 || delta.compareTo(BigDecimal.ONE)>=0
                : delta.signum()>=0 || delta.compareTo(BigDecimal.ONE.negate())<=0)
            return false;
        BigDecimal mid=q.ask().add(q.bid(),MC).divide(BigDecimal.valueOf(2),MC);
        return q.ask().subtract(q.bid(),MC).multiply(TEN_THOUSAND,MC)
                .divide(mid,MC).compareTo(BigDecimal.valueOf(p.maxOptionSpreadBps()))<=0;
    }
    private static boolean validIv(BigDecimal v,int max) {
        return positive(v)&&v.compareTo(BigDecimal.valueOf(max))<=0;
    }
    private static boolean provider(String v) {
        return "UPSTOX_ANALYTICS".equals(v)||"ANGEL_ONE_SMARTAPI".equals(v);
    }
    private static boolean optionKey(String v) {
        return v!=null && v.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    private static boolean equityKey(String v) {
        return v!=null && v.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}");
    }
    private static boolean positive(BigDecimal v) { return v!=null&&v.signum()>0; }
    private static boolean between(int v,int l,int h) { return v>=l&&v<=h; }
    private static BigDecimal rounded(BigDecimal v) {
        return v==null?null:v.setScale(4,RoundingMode.HALF_UP);
    }
    private static Decision blocked(Status status,String reason,Instant at) {
        return measured(status,reason,at,0,null,null,null,null,0,null,null,null);
    }
    private static Decision measured(Status status,String reason,Instant at,int observations,
                                     BigDecimal beta,BigDecimal value,BigDecimal exposure,
                                     String type,int lots,BigDecimal hedge,BigDecimal residual,
                                     BigDecimal cost) {
        return new Decision(status,reason,STRATEGY_ID,at,observations,
                rounded(beta),rounded(value),rounded(exposure),type,lots,
                rounded(hedge),rounded(residual),rounded(cost),true);
    }
}
