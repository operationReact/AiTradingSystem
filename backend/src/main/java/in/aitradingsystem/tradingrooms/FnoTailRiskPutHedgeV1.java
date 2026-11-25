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
 * STR-206 (37/43): retrospective tail-scenario research ONLY. Replays the
 * largest original-source historical NIFTY daily downside move against CURRENT
 * broker-verified long equity quantities, then values a hypothetical PURCHASED
 * NIFTY put by expiry intrinsic payoff IF that same downside level happened
 * to persist to the option's expiry. Neither an actual backtest nor a forecast.
 *
 * Never interprets hypothetical payoff as realized P&L, insurance or a trade.
 */
public final class FnoTailRiskPutHedgeV1 {
    public static final String STRATEGY_ID = "strategy.STR-206";
    public static final String INDEX = FnoLongOptionCrossoverV1.UNDERLYING;
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = BigDecimal.valueOf(10000);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime CLOSE = LocalTime.of(15, 30);
    private static final LocalTime OPEN = LocalTime.of(9, 15);
    private FnoTailRiskPutHedgeV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_HEDGE, RESEARCH_CANDIDATE }
    public record Rights(boolean brokerHoldingsResearch, boolean indexHistoryResearch,
                         boolean equityHistoryResearch, boolean optionChainResearch,
                         boolean originalGreeksResearch, boolean instrumentMasterResearch) {}
    /** Only source-confirmed owned and positive cash equity holdings supported. */
    public record Holding(String key, long shares, Instant brokerFirstKnownAt,
                          boolean brokerConfirmed) {}
    public record EquityQuote(String key, String provider, BigDecimal priceInr,
                              Instant exchangeAt, Instant firstKnownAt,
                              boolean sourceVerified) {}
    public record IndexClose(LocalDate session, String provider, BigDecimal price,
                             Instant exchangeCloseAt, Instant firstKnownAt,
                             boolean sourceVerified) {}
    public record EquityClose(String key, String provider, BigDecimal price,
                              Instant exchangeCloseAt, Instant firstKnownAt,
                              boolean sourceVerified) {}
    public record CompletedSession(IndexClose index, List<EquityClose> equities) {}
    public record PutContract(String key, String index, BigDecimal strike, BigDecimal tick,
                              LocalDate listedFrom, LocalDate expiry, int officialLot,
                              boolean sourceListed) {}
    public record PutQuote(String key, String provider, BigDecimal bid, BigDecimal ask,
                           BigDecimal originalDelta, BigDecimal originalIvPercentAnnualized,
                           long openInterestLots, long askDepthLots, Instant exchangeAt,
                           Instant firstKnownAt, boolean sourceVerified) {}
    public record Snapshot(String provider, String indexKey, BigDecimal indexSpot,
                           List<EquityQuote> equities, PutQuote put,
                           Instant exchangeAt, Instant firstKnownAt,
                           boolean sourceVerified) {}
    public record Policy(int minCompletedSessions, int maxQuoteAgeSeconds,
                         int maxQuoteSkewSeconds, int maxPutSpreadBps,
                         long minPutOiLots, int maxAnnualizedIvPercent,
                         int maxBuyLots, int buySlippageBps,
                         int maxPremiumBudgetBps, int minStressDropBps,
                         int maxStressDropBps, int minNetProtectionBps,
                         BigDecimal modeledOrderFeeInr) {}
    /** All monetary amounts are expiry-scenario proxies, not actual fills or P&L. */
    public record Decision(Status status, String reasonCode, String strategyId,
                           Instant asOf, int completedReturnObservations,
                           LocalDate worstObservedIndexSession, BigDecimal worstObservedIndexDropBps,
                           BigDecimal currentPortfolioValueInr,
                           BigDecimal scenarioIndexAtHypotheticalExpiry,
                           BigDecimal hypotheticalUnhedgedPortfolioLossInr,
                           int hypotheticalBoughtPutLots,
                           BigDecimal hypotheticalPutPurchaseDebitInr,
                           BigDecimal hypotheticalPutIntrinsicAtExpiryInr,
                           BigDecimal hypotheticalResidualScenarioLossInr,
                           boolean hindsightReplayOnly) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean paperTradeAuthorized() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(List<Holding> holdings, PutContract put,
                                    Snapshot snapshot, List<CompletedSession> completed,
                                    Rights rights, Policy policy, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.brokerHoldingsResearch() || !rights.indexHistoryResearch()
                || !rights.equityHistoryResearch() || !rights.optionChainResearch()
                || !rights.originalGreeksResearch() || !rights.instrumentMasterResearch())
            return blocked(Status.BLOCKED_RIGHTS, "TAIL_PUT_SOURCE_RIGHTS_UNVERIFIED", asOf);
        if (!validPolicy(policy) || !validSnapshot(snapshot, policy, asOf)
                || !validPut(put, snapshot, asOf)
                || !validPutQuote(snapshot.put(), put, snapshot, policy, asOf))
            return blocked(Status.BLOCKED_DATA, "TAIL_PUT_INSTRUMENT_OR_QUOTE_INVALID", asOf);

        Map<String, BigDecimal> currentValues = new HashMap<>();
        if (!validHoldings(holdings, snapshot, policy, currentValues))
            return blocked(Status.BLOCKED_DATA, "TAIL_PUT_BROKER_HOLDINGS_INVALID", asOf);
        if (!validCompletedHistory(completed, currentValues.keySet(), snapshot, policy))
            return blocked(Status.BLOCKED_DATA, "TAIL_PUT_ORIGINAL_HISTORY_INVALID", asOf);

        BigDecimal value = BigDecimal.ZERO;
        for (BigDecimal stockValue : currentValues.values()) value = value.add(stockValue, MC);
        if (value.signum() <= 0)
            return blocked(Status.BLOCKED_DATA, "TAIL_PUT_PORTFOLIO_VALUE_INVALID", asOf);

        // Replay the *same* historical index-tail session for all equity names;
        // never cherry-pick each stock's independently worst historical session.
        BigDecimal worstReturn = BigDecimal.ZERO;
        int worstIndex = -1;
        for (int i = 1; i < completed.size(); i++) {
            BigDecimal from = completed.get(i - 1).index().price();
            BigDecimal to = completed.get(i).index().price();
            BigDecimal observed = to.divide(from, MC).subtract(BigDecimal.ONE, MC);
            if (observed.compareTo(worstReturn) < 0) {
                worstReturn = observed;
                worstIndex = i;
            }
        }
        if (worstIndex < 0)
            return measured(Status.NO_HEDGE, "TAIL_PUT_NO_OBSERVED_INDEX_DOWNSIDE",
                    asOf, completed.size() - 1, null, BigDecimal.ZERO, value,
                    null, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        BigDecimal indexDropBps = worstReturn.negate().multiply(BPS, MC);
        BigDecimal indexScenario = snapshot.indexSpot().multiply(
                BigDecimal.ONE.add(worstReturn, MC), MC);
        if (indexDropBps.compareTo(BigDecimal.valueOf(policy.minStressDropBps())) < 0
                || indexDropBps.compareTo(BigDecimal.valueOf(policy.maxStressDropBps())) > 0)
            return measured(Status.NO_HEDGE, "TAIL_PUT_OBSERVED_SHOCK_OUTSIDE_POLICY",
                    asOf, completed.size() - 1, completed.get(worstIndex).index().session(),
                    indexDropBps, value, indexScenario, BigDecimal.ZERO,
                    0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        // Scenario uses *today's* holdings/weights and the equity return from
        // the same past tail day. It is not an actual historic holdings ledger.
        Map<String,BigDecimal> previous = closeMap(completed.get(worstIndex - 1).equities());
        BigDecimal portfolioChange = BigDecimal.ZERO;
        for (EquityClose equity : completed.get(worstIndex).equities()) {
            BigDecimal equityReturn = equity.price().divide(previous.get(equity.key()), MC)
                    .subtract(BigDecimal.ONE, MC);
            portfolioChange = portfolioChange.add(
                    currentValues.get(equity.key()).multiply(equityReturn, MC), MC);
        }
        BigDecimal unhedgedLoss = portfolioChange.negate().max(BigDecimal.ZERO);
        if (unhedgedLoss.signum() == 0)
            return measured(Status.NO_HEDGE, "TAIL_PUT_HISTORICAL_PORTFOLIO_PROXY_NOT_LOSS",
                    asOf, completed.size() - 1, completed.get(worstIndex).index().session(),
                    indexDropBps, value, indexScenario, unhedgedLoss,
                    0, BigDecimal.ZERO, BigDecimal.ZERO, unhedgedLoss);

        // Only guaranteed expiry intrinsic in the explicitly hypothetical case
        // that the tail shock level persisted until this *real* put expiry.
        // No invented interim option mark, bid, IV repricing or profit prediction.
        BigDecimal oneLotIntrinsic = put.strike().subtract(indexScenario, MC)
                .max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(put.officialLot()), MC);
        if (oneLotIntrinsic.signum() == 0)
            return measured(Status.NO_HEDGE, "TAIL_PUT_SCENARIO_STRIKE_NOT_REACHED",
                    asOf, completed.size() - 1, completed.get(worstIndex).index().session(),
                    indexDropBps, value, indexScenario, unhedgedLoss,
                    0, BigDecimal.ZERO, BigDecimal.ZERO, unhedgedLoss);

        PutQuote quote = snapshot.put();
        int limit = (int) Math.min((long) policy.maxBuyLots(), quote.askDepthLots());
        BigDecimal unitDebit = quote.ask().multiply(
                BPS.add(BigDecimal.valueOf(policy.buySlippageBps())).divide(BPS, MC), MC)
                .multiply(BigDecimal.valueOf(put.officialLot()), MC);
        BigDecimal maxBudget = value.multiply(BigDecimal.valueOf(
                policy.maxPremiumBudgetBps()), MC).divide(BPS, MC);

        int selectedLots = 0;
        BigDecimal bestResidual = unhedgedLoss;
        BigDecimal bestCost = BigDecimal.ZERO;
        BigDecimal bestIntrinsic = BigDecimal.ZERO;
        for (int lots = 1; lots <= limit; lots++) {
            BigDecimal qty = BigDecimal.valueOf(lots);
            BigDecimal debit = unitDebit.multiply(qty, MC)
                    .add(policy.modeledOrderFeeInr(), MC);
            if (debit.compareTo(maxBudget) > 0) continue;
            BigDecimal intrinsic = oneLotIntrinsic.multiply(qty, MC);
            BigDecimal netBenefit = intrinsic.subtract(debit, MC);
            if (netBenefit.signum() <= 0) continue;
            BigDecimal residual = unhedgedLoss.subtract(netBenefit, MC).max(BigDecimal.ZERO);
            if (residual.compareTo(bestResidual) < 0
                    || (selectedLots != 0 && residual.compareTo(bestResidual) == 0
                    && debit.compareTo(bestCost) < 0)) {
                bestResidual = residual;
                bestCost = debit;
                bestIntrinsic = intrinsic;
                selectedLots = lots;
            }
        }
        if (selectedLots == 0)
            return measured(Status.NO_HEDGE, "TAIL_PUT_NO_AFFORDABLE_NET_SCENARIO_PROTECTION",
                    asOf, completed.size() - 1, completed.get(worstIndex).index().session(),
                    indexDropBps, value, indexScenario, unhedgedLoss,
                    0, BigDecimal.ZERO, BigDecimal.ZERO, unhedgedLoss);

        BigDecimal reductionBps = unhedgedLoss.subtract(bestResidual, MC)
                .multiply(BPS, MC).divide(unhedgedLoss, MC);
        if (reductionBps.compareTo(BigDecimal.valueOf(policy.minNetProtectionBps())) < 0)
            return measured(Status.NO_HEDGE, "TAIL_PUT_SCENARIO_REDUCTION_TOO_SMALL",
                    asOf, completed.size() - 1, completed.get(worstIndex).index().session(),
                    indexDropBps, value, indexScenario, unhedgedLoss,
                    0, BigDecimal.ZERO, BigDecimal.ZERO, unhedgedLoss);

        return measured(Status.RESEARCH_CANDIDATE, "TAIL_PUT_HYPOTHETICAL_EXPIRY_SCENARIO_ONLY",
                asOf, completed.size() - 1, completed.get(worstIndex).index().session(),
                indexDropBps, value, indexScenario, unhedgedLoss,
                selectedLots, bestCost, bestIntrinsic, bestResidual);
    }

    private static boolean validPolicy(Policy p) {
        return p != null && between(p.minCompletedSessions(), 61, 252)
                && between(p.maxQuoteAgeSeconds(), 30, 600)
                && between(p.maxQuoteSkewSeconds(), 0, 120)
                && between(p.maxPutSpreadBps(), 1, 5000)
                && p.minPutOiLots() >= 1
                && between(p.maxAnnualizedIvPercent(), 30, 500)
                && between(p.maxBuyLots(), 1, 500)
                && between(p.buySlippageBps(), 0, 1000)
                && between(p.maxPremiumBudgetBps(), 1, 5000)
                && between(p.minStressDropBps(), 100, 5000)
                && between(p.maxStressDropBps(), 200, 8000)
                && p.maxStressDropBps() >= p.minStressDropBps()
                && between(p.minNetProtectionBps(), 10, 9900)
                && positive(p.modeledOrderFeeInr());
    }
    private static boolean validSnapshot(Snapshot s, Policy p, Instant now) {
        if (s == null || !s.sourceVerified() || !provider(s.provider())
                || !INDEX.equals(s.indexKey()) || !positive(s.indexSpot())
                || s.exchangeAt() == null || s.firstKnownAt() == null
                || s.firstKnownAt().isBefore(s.exchangeAt())
                || s.firstKnownAt().isAfter(now)
                || Duration.between(s.exchangeAt(), now).compareTo(
                    Duration.ofSeconds(p.maxQuoteAgeSeconds())) > 0)
            return false;
        var session = s.exchangeAt().atZone(IST);
        return session.getDayOfWeek().getValue() <= 5
                && !session.toLocalTime().isBefore(OPEN)
                && !session.toLocalTime().isAfter(CLOSE);
    }
    private static boolean validPut(PutContract p, Snapshot s, Instant now) {
        if (p == null || !p.sourceListed() || !optionKey(p.key()) || !INDEX.equals(p.index())
                || !positive(p.strike()) || !positive(p.tick()) || p.officialLot() < 1
                || p.strike().remainder(p.tick()).signum() != 0
                || p.listedFrom() == null || p.expiry() == null
                || p.strike().compareTo(s.indexSpot()) >= 0)
            return false;
        LocalDate day = now.atZone(IST).toLocalDate();
        long dte = ChronoUnit.DAYS.between(day, p.expiry());
        return !p.listedFrom().isAfter(day) && dte >= 7 && dte <= 45;
    }
    private static boolean validPutQuote(PutQuote q, PutContract c, Snapshot s,
                                         Policy p, Instant now) {
        if (q == null || !q.sourceVerified() || !c.key().equals(q.key())
                || !s.provider().equals(q.provider()) || !positive(q.bid())
                || !positive(q.ask()) || q.ask().compareTo(q.bid()) < 0
                || !positive(q.originalIvPercentAnnualized())
                || q.originalIvPercentAnnualized().compareTo(
                       BigDecimal.valueOf(p.maxAnnualizedIvPercent())) > 0
                || q.originalDelta() == null || q.originalDelta().signum() >= 0
                || q.originalDelta().compareTo(BigDecimal.ONE.negate()) <= 0
                || q.openInterestLots() < p.minPutOiLots() || q.askDepthLots() < 1
                || q.exchangeAt() == null || q.firstKnownAt() == null
                || q.firstKnownAt().isBefore(q.exchangeAt())
                || q.firstKnownAt().isAfter(now)
                || Duration.between(q.exchangeAt(), now).compareTo(
                    Duration.ofSeconds(p.maxQuoteAgeSeconds())) > 0
                || Math.abs(Duration.between(q.exchangeAt(),s.exchangeAt()).getSeconds())
                    > p.maxQuoteSkewSeconds()) return false;
        BigDecimal mid = q.bid().add(q.ask(), MC).divide(BigDecimal.valueOf(2), MC);
        return q.ask().subtract(q.bid(), MC).multiply(BPS, MC).divide(mid, MC)
                .compareTo(BigDecimal.valueOf(p.maxPutSpreadBps())) <= 0;
    }
    private static boolean validHoldings(List<Holding> h, Snapshot s, Policy p,
                                         Map<String, BigDecimal> values) {
        if (h == null || h.isEmpty() || h.size() > 15
                || s.equities() == null || s.equities().size() != h.size()) return false;
        Map<String,Long> shares = new HashMap<>();
        for (Holding holding : h) {
            if (holding == null || !equityKey(holding.key()) || holding.shares() <= 0
                    || holding.shares() > 10_000_000L || !holding.brokerConfirmed()
                    || holding.brokerFirstKnownAt() == null
                    || holding.brokerFirstKnownAt().isAfter(s.exchangeAt())
                    || shares.putIfAbsent(holding.key(),holding.shares()) != null) return false;
        }
        for (EquityQuote quote : s.equities()) {
            if (quote == null || !quote.sourceVerified()
                    || !shares.containsKey(quote.key()) || values.containsKey(quote.key())
                    || !s.provider().equals(quote.provider()) || !positive(quote.priceInr())
                    || quote.exchangeAt() == null || quote.firstKnownAt() == null
                    || quote.firstKnownAt().isBefore(quote.exchangeAt())
                    || quote.firstKnownAt().isAfter(s.firstKnownAt())
                    || Math.abs(Duration.between(quote.exchangeAt(),s.exchangeAt()).getSeconds())
                        > p.maxQuoteSkewSeconds()) return false;
            values.put(quote.key(), quote.priceInr()
                    .multiply(BigDecimal.valueOf(shares.get(quote.key())), MC));
        }
        return values.size() == h.size();
    }
    private static boolean validCompletedHistory(List<CompletedSession> series,
                                                 Set<String> keys, Snapshot snapshot,
                                                 Policy policy) {
        if (series == null || series.size() < policy.minCompletedSessions()
                || series.size() > 300) return false;
        LocalDate previous = null;
        for (CompletedSession bar : series) {
            if (bar == null || bar.index() == null) return false;
            IndexClose index = bar.index();
            if (index.session() == null || index.session().getDayOfWeek().getValue() > 5
                    || !index.sourceVerified() || !snapshot.provider().equals(index.provider())
                    || !positive(index.price()) || index.exchangeCloseAt() == null
                    || index.firstKnownAt() == null) return false;
            Instant expected = index.session().atTime(CLOSE).atZone(IST).toInstant();
            if (!expected.equals(index.exchangeCloseAt())
                    || index.firstKnownAt().isBefore(expected)
                    || index.firstKnownAt().isAfter(expected.plus(Duration.ofHours(36)))
                    || !index.firstKnownAt().isBefore(snapshot.exchangeAt())
                    || previous != null && (ChronoUnit.DAYS.between(previous,index.session()) < 1
                        || ChronoUnit.DAYS.between(previous,index.session()) > 8))
                return false;
            if (bar.equities() == null || bar.equities().size() != keys.size()) return false;
            Set<String> seen = new HashSet<>();
            for (EquityClose e : bar.equities()) {
                if (e == null || !keys.contains(e.key()) || !seen.add(e.key())
                        || !e.sourceVerified() || !snapshot.provider().equals(e.provider())
                        || !positive(e.price()) || e.exchangeCloseAt() == null
                        || !expected.equals(e.exchangeCloseAt()) || e.firstKnownAt() == null
                        || e.firstKnownAt().isBefore(expected)
                        || e.firstKnownAt().isAfter(expected.plus(Duration.ofHours(36)))
                        || !e.firstKnownAt().isBefore(snapshot.exchangeAt())) return false;
            }
            previous = index.session();
        }
        long lag = ChronoUnit.DAYS.between(
                previous, snapshot.exchangeAt().atZone(IST).toLocalDate());
        return lag >= 1 && lag <= 5;
    }
    private static Map<String,BigDecimal> closeMap(List<EquityClose> equities) {
        Map<String,BigDecimal> closes = new HashMap<>();
        for (EquityClose e : equities) closes.put(e.key(),e.price());
        return closes;
    }
    private static boolean provider(String s) {
        return "UPSTOX_ANALYTICS".equals(s) || "ANGEL_ONE_SMARTAPI".equals(s);
    }
    private static boolean equityKey(String k) {
        return k != null && k.startsWith("NSE_EQ|")
                && k.substring(7).matches("[A-Za-z0-9._-]{1,64}");
    }
    private static boolean optionKey(String k) {
        return k != null && k.startsWith("NSE_FO|")
                && k.substring(7).matches("[A-Za-z0-9._-]{1,64}");
    }
    private static boolean positive(BigDecimal v) { return v != null && v.signum() > 0; }
    private static boolean between(int n,int lo,int hi) { return n >= lo && n <= hi; }
    private static BigDecimal rounded(BigDecimal v) {
        return v == null ? null : v.setScale(4, RoundingMode.HALF_UP);
    }
    private static Decision blocked(Status s,String code,Instant at) {
        return measured(s,code,at,0,null,null,null,null,null,0,null,null,null);
    }
    private static Decision measured(Status status,String reason,Instant at,
                                     int returns,LocalDate worstDay,BigDecimal drop,
                                     BigDecimal value,BigDecimal indexScenario,
                                     BigDecimal unhedgedLoss,int buyLots,
                                     BigDecimal cost,BigDecimal intrinsic,BigDecimal residual) {
        return new Decision(status,reason,STRATEGY_ID,at,returns,worstDay,
                rounded(drop),rounded(value),rounded(indexScenario),
                rounded(unhedgedLoss),buyLots,rounded(cost),rounded(intrinsic),
                rounded(residual),true);
    }
}
