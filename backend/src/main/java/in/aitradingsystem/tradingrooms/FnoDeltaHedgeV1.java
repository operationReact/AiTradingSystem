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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * STR-204 (35/43): source-proven delta hedging RESEARCH for an ALREADY
 * BROKER-CONFIRMED options portfolio and separately verified NIFTY futures.
 * The signed integer futures lots are a hypothetical adjustment, never an order.
 *
 * Unlike STR-203, this makes no gamma, theta, volatility or P&L claim.
 */
public final class FnoDeltaHedgeV1 {
    private FnoDeltaHedgeV1() {}
    public static final String STRATEGY_ID = "strategy.STR-204";
    public static final String INDEX = "NSE_INDEX|Nifty 50";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = BigDecimal.valueOf(10000);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN = LocalTime.of(9,15);
    private static final LocalTime CLOSE = LocalTime.of(15,30);

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_REBALANCE, RESEARCH_REBALANCE }
    public record Rights(boolean verifiedBrokerPositions, boolean indexResearch,
                         boolean listedOptionChainResearch, boolean originalGreeksResearch,
                         boolean listedFuturesResearch, boolean completedHistoryResearch) {}
    public record Instrument(String key, String index, String type, BigDecimal strike,
                             BigDecimal tick, LocalDate listedFrom, LocalDate expiry,
                             int lotSize, boolean sourceListed) {}
    public record OptionPosition(Instrument instrument, int signedLots,
                                 Instant brokerFirstKnownAt, boolean brokerVerified) {}
    public record FutureInstrument(String key, String index, BigDecimal tick,
                                   LocalDate listedFrom, LocalDate expiry,
                                   int lotSize, boolean sourceListed) {}
    public record FuturesPosition(int signedLots, Instant brokerFirstKnownAt,
                                  boolean brokerVerified) {}
    public record CompletedClose(LocalDate session, String provider,
                                 BigDecimal indexClose, Instant exchangeCloseAt,
                                 Instant firstKnownAt, boolean sourceVerified) {}
    /** Delta is per underlying unit, not per option lot or monetary value. */
    public record OptionQuote(String provider, String key, BigDecimal bid, BigDecimal ask,
                              BigDecimal originalDelta, long openInterestLots,
                              Instant exchangeAt, Instant firstKnownAt,
                              boolean sourceVerified) {}
    public record Snapshot(String provider, String indexKey, String futureKey,
                           BigDecimal indexSpot, BigDecimal futureBid,
                           BigDecimal futureAsk, long futureBidDepthLots,
                           long futureAskDepthLots, List<OptionQuote> optionQuotes,
                           Instant exchangeAt, Instant firstKnownAt,
                           boolean sourceVerified) {}
    public record Policy(int maxQuoteAgeSeconds, int maxQuoteSkewSeconds,
                         int maxFutureSpreadBps, int maxOptionSpreadBps,
                         long minOptionOiLots, int maxAbsoluteFutureLots,
                         int buySlippageBps, int sellSlippageBps,
                         int maxModeledCostBps, BigDecimal maxResidualDeltaUnits,
                         BigDecimal perHedgeOrderFeeInr) {}
    public record Decision(Status status, String reasonCode, String strategyId,
                           Instant asOf, BigDecimal optionDeltaUnits,
                           BigDecimal currentNetDeltaUnits,
                           BigDecimal hypotheticalResidualDeltaUnits,
                           int observedFuturesLots, int hypotheticalTargetFuturesLots,
                           int hypotheticalChangeFuturesLots,
                           BigDecimal hypotheticalOneWayCostInr,
                           boolean sourceBasedResearchOnly) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean liveTradeAuthorized() { return false; }
        public boolean paperTradeAuthorized() { return false; }
    }

    public static Decision evaluate(List<OptionPosition> options,
                                    FutureInstrument future, FuturesPosition currentFutures,
                                    Snapshot snapshot, List<CompletedClose> history,
                                    Rights rights, Policy policy, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.verifiedBrokerPositions() || !rights.indexResearch()
                || !rights.listedOptionChainResearch() || !rights.originalGreeksResearch()
                || !rights.listedFuturesResearch() || !rights.completedHistoryResearch())
            return blocked(Status.BLOCKED_RIGHTS, "DELTA_HEDGE_RIGHTS_UNVERIFIED", asOf);
        if (!validPolicy(policy) || !validFuture(future, asOf) || !validFuturesPosition(currentFutures, snapshot)
                || !validSnapshot(snapshot, future, policy, asOf)
                || !validHistory(history, snapshot))
            return blocked(Status.BLOCKED_DATA, "DELTA_HEDGE_SOURCE_OR_PROVENANCE_INVALID", asOf);
        if (options == null || options.isEmpty() || options.size() > 16
                || snapshot.optionQuotes() == null || snapshot.optionQuotes().size() != options.size())
            return blocked(Status.BLOCKED_DATA, "DELTA_HEDGE_POSITIONS_OR_QUOTES_INVALID", asOf);

        Set<String> seen = new HashSet<>();
        BigDecimal optionDelta = BigDecimal.ZERO;
        for (OptionPosition pos : options) {
            if (!validPosition(pos, snapshot, asOf) || !seen.add(pos.instrument().key()))
                return blocked(Status.BLOCKED_DATA, "DELTA_HEDGE_OPTION_POSITION_INVALID", asOf);
            OptionQuote quote = null;
            for (OptionQuote candidate : snapshot.optionQuotes()) {
                if (candidate != null && pos.instrument().key().equals(candidate.key())) {
                    if (quote != null) return blocked(Status.BLOCKED_DATA, "DELTA_HEDGE_DUPLICATE_QUOTE", asOf);
                    quote = candidate;
                }
            }
            if (!validOptionQuote(quote, pos.instrument(), snapshot, policy, asOf))
                return blocked(Status.BLOCKED_DATA, "DELTA_HEDGE_ORIGINAL_DELTA_OR_LIQUIDITY_INVALID", asOf);
            BigDecimal units = BigDecimal.valueOf((long) pos.signedLots())
                    .multiply(BigDecimal.valueOf(pos.instrument().lotSize()), MC);
            optionDelta = optionDelta.add(units.multiply(quote.originalDelta(), MC), MC);
        }
        // All quotes must have been consumed by unique broker-confirmed positions.
        for (OptionQuote quote : snapshot.optionQuotes()) {
            if (quote == null || !seen.contains(quote.key()))
                return blocked(Status.BLOCKED_DATA, "DELTA_HEDGE_UNMATCHED_OPTION_QUOTE", asOf);
        }

        BigDecimal futLot = BigDecimal.valueOf(future.lotSize());
        BigDecimal currentDelta = optionDelta.add(futLot.multiply(
                BigDecimal.valueOf(currentFutures.signedLots()), MC), MC);
        // HALF_UP is symmetric away from zero for signed integer lots.
        BigDecimal roundedTarget = optionDelta.negate().divide(futLot, MC)
                .setScale(0, RoundingMode.HALF_UP);
        if (roundedTarget.abs().compareTo(
                BigDecimal.valueOf(policy.maxAbsoluteFutureLots())) > 0)
            return blocked(Status.BLOCKED_DATA, "DELTA_HEDGE_POSITION_LIMIT_EXCEEDED", asOf);
        int target = roundedTarget.intValueExact();
        long changeLong = (long) target - currentFutures.signedLots();
        if (changeLong < Integer.MIN_VALUE || changeLong > Integer.MAX_VALUE)
            return blocked(Status.BLOCKED_DATA, "DELTA_HEDGE_CHANGE_OVERFLOW", asOf);
        int change = (int) changeLong;
        BigDecimal residual = optionDelta.add(futLot.multiply(BigDecimal.valueOf(target), MC), MC);

        if (currentDelta.abs().compareTo(policy.maxResidualDeltaUnits()) <= 0
                || change == 0 || residual.abs().compareTo(currentDelta.abs()) >= 0)
            return measured(Status.NO_REBALANCE, "DELTA_HEDGE_NO_INCREMENTAL_IMPROVEMENT", asOf,
                    optionDelta, currentDelta, residual, currentFutures.signedLots(), target, 0, BigDecimal.ZERO);
        if (residual.abs().compareTo(policy.maxResidualDeltaUnits()) > 0)
            return measured(Status.NO_REBALANCE, "DELTA_HEDGE_INTEGER_LOT_RESIDUAL_TOO_LARGE", asOf,
                    optionDelta, currentDelta, residual, currentFutures.signedLots(), target, 0, BigDecimal.ZERO);
        long available = change > 0 ? snapshot.futureAskDepthLots() : snapshot.futureBidDepthLots();
        if (Math.abs(changeLong) > available)
            return measured(Status.NO_REBALANCE, "DELTA_HEDGE_FUTURES_DEPTH_INSUFFICIENT", asOf,
                    optionDelta, currentDelta, residual, currentFutures.signedLots(), target, 0, BigDecimal.ZERO);

        BigDecimal mid = snapshot.futureBid().add(snapshot.futureAsk(), MC)
                .divide(BigDecimal.valueOf(2), MC);
        BigDecimal sidePrice = change > 0 ? snapshot.futureAsk() : snapshot.futureBid();
        int slipBps = change > 0 ? policy.buySlippageBps() : policy.sellSlippageBps();
        BigDecimal effectivePrice = change > 0
                ? sidePrice.multiply(BPS.add(BigDecimal.valueOf(slipBps)).divide(BPS, MC), MC)
                : sidePrice.multiply(BPS.subtract(BigDecimal.valueOf(slipBps)).divide(BPS, MC), MC);
        BigDecimal quantity = futLot.multiply(BigDecimal.valueOf(Math.abs(changeLong)), MC);
        BigDecimal cost = effectivePrice.subtract(mid, MC).abs()
                .multiply(quantity, MC).add(policy.perHedgeOrderFeeInr(), MC);
        BigDecimal notional = mid.multiply(quantity, MC);
        if (cost.multiply(BPS, MC).divide(notional, MC)
                .compareTo(BigDecimal.valueOf(policy.maxModeledCostBps())) > 0)
            return measured(Status.NO_REBALANCE, "DELTA_HEDGE_MODELED_COST_EXCEEDS_POLICY", asOf,
                    optionDelta, currentDelta, residual, currentFutures.signedLots(), target, 0, cost);
        return measured(Status.RESEARCH_REBALANCE, "DELTA_HEDGE_HYPOTHETICAL_ONLY", asOf,
                optionDelta, currentDelta, residual, currentFutures.signedLots(), target, change, cost);
    }

    private static boolean validPolicy(Policy p) {
        return p != null && between(p.maxQuoteAgeSeconds(), 20, 600)
                && between(p.maxQuoteSkewSeconds(), 0, 120)
                && between(p.maxFutureSpreadBps(), 1, 2000)
                && between(p.maxOptionSpreadBps(), 1, 5000)
                && p.minOptionOiLots() >= 1
                && between(p.maxAbsoluteFutureLots(), 1, 5000)
                && between(p.buySlippageBps(), 0, 1000)
                && between(p.sellSlippageBps(), 0, 1000)
                && between(p.maxModeledCostBps(), 1, 2000)
                && positive(p.maxResidualDeltaUnits())
                && positive(p.perHedgeOrderFeeInr());
    }
    private static boolean validFuture(FutureInstrument f, Instant at) {
        if (f == null || !f.sourceListed() || !key(f.key()) || !INDEX.equals(f.index())
                || !positive(f.tick()) || f.lotSize() < 1 || f.listedFrom() == null
                || f.expiry() == null) return false;
        LocalDate day = at.atZone(IST).toLocalDate();
        long dte = ChronoUnit.DAYS.between(day, f.expiry());
        return !f.listedFrom().isAfter(day) && dte >= 2 && dte <= 60;
    }
    private static boolean validFuturesPosition(FuturesPosition p, Snapshot s) {
        return p != null && p.brokerVerified() && p.brokerFirstKnownAt() != null
                && s != null && s.exchangeAt() != null
                && !p.brokerFirstKnownAt().isAfter(s.exchangeAt())
                && Math.abs((long) p.signedLots()) <= 5000;
    }
    private static boolean validSnapshot(Snapshot s, FutureInstrument f, Policy p, Instant asOf) {
        if (s == null || !s.sourceVerified() || !provider(s.provider())
                || !INDEX.equals(s.indexKey()) || !f.key().equals(s.futureKey())
                || !positive(s.indexSpot())
                || !spread(s.futureBid(), s.futureAsk(), p.maxFutureSpreadBps())
                || s.futureBidDepthLots() < 1 || s.futureAskDepthLots() < 1
                || s.exchangeAt() == null || s.firstKnownAt() == null
                || s.firstKnownAt().isBefore(s.exchangeAt())
                || s.firstKnownAt().isAfter(asOf)
                || s.exchangeAt().isAfter(asOf)
                || Duration.between(s.exchangeAt(), asOf).compareTo(
                        Duration.ofSeconds(p.maxQuoteAgeSeconds())) > 0) return false;
        var z = s.exchangeAt().atZone(IST);
        return z.getDayOfWeek().getValue() <= 5
                && !z.toLocalTime().isBefore(OPEN) && !z.toLocalTime().isAfter(CLOSE);
    }
    private static boolean validHistory(List<CompletedClose> bars, Snapshot snap) {
        if (bars == null || bars.size() < 35 || bars.size() > 512) return false;
        LocalDate last = null;
        for (CompletedClose b : bars) {
            if (b == null || !b.sourceVerified() || b.session() == null
                    || b.session().getDayOfWeek().getValue() > 5
                    || !snap.provider().equals(b.provider()) || !positive(b.indexClose())
                    || b.exchangeCloseAt() == null || b.firstKnownAt() == null
                    || !b.exchangeCloseAt().equals(b.session().atTime(CLOSE).atZone(IST).toInstant())
                    || b.firstKnownAt().isBefore(b.exchangeCloseAt())
                    || !b.firstKnownAt().isBefore(snap.exchangeAt())
                    || (last != null && (ChronoUnit.DAYS.between(last, b.session()) < 1
                    || ChronoUnit.DAYS.between(last, b.session()) > 5))) return false;
            last = b.session();
        }
        return last != null && ChronoUnit.DAYS.between(last,
                snap.exchangeAt().atZone(IST).toLocalDate()) <= 4;
    }
    private static boolean validPosition(OptionPosition p, Snapshot s, Instant asOf) {
        if (p == null || !p.brokerVerified() || p.signedLots() == 0
                || Math.abs((long) p.signedLots()) > 5000
                || p.brokerFirstKnownAt() == null
                || p.brokerFirstKnownAt().isAfter(s.exchangeAt())) return false;
        Instrument i = p.instrument();
        if (i == null || !i.sourceListed() || !key(i.key()) || !INDEX.equals(i.index())
                || !("CE".equals(i.type()) || "PE".equals(i.type()))
                || !positive(i.strike()) || !positive(i.tick()) || i.lotSize() < 1
                || i.strike().remainder(i.tick()).signum() != 0
                || i.listedFrom() == null || i.expiry() == null) return false;
        LocalDate day = asOf.atZone(IST).toLocalDate();
        long dte = ChronoUnit.DAYS.between(day, i.expiry());
        return !i.listedFrom().isAfter(day) && dte >= 2 && dte <= 60;
    }
    private static boolean validOptionQuote(OptionQuote q, Instrument i, Snapshot s,
                                            Policy p, Instant asOf) {
        if (q == null || !q.sourceVerified() || !s.provider().equals(q.provider())
                || !i.key().equals(q.key())
                || !spread(q.bid(), q.ask(), p.maxOptionSpreadBps())
                || q.openInterestLots() < p.minOptionOiLots() || q.originalDelta() == null
                || q.exchangeAt() == null || q.firstKnownAt() == null
                || q.firstKnownAt().isBefore(q.exchangeAt())
                || q.firstKnownAt().isAfter(asOf)
                || Duration.between(q.exchangeAt(), asOf).compareTo(
                        Duration.ofSeconds(p.maxQuoteAgeSeconds())) > 0
                || Math.abs(Duration.between(q.exchangeAt(), s.exchangeAt()).getSeconds())
                    > p.maxQuoteSkewSeconds()) return false;
        BigDecimal delta = q.originalDelta();
        return "CE".equals(i.type()) ? delta.signum() > 0 && delta.compareTo(BigDecimal.ONE) < 0
                : delta.signum() < 0 && delta.compareTo(BigDecimal.ONE.negate()) > 0;
    }
    private static boolean spread(BigDecimal bid, BigDecimal ask, int maxBps) {
        if (!positive(bid) || !positive(ask) || ask.compareTo(bid) < 0) return false;
        BigDecimal mid = bid.add(ask, MC).divide(BigDecimal.valueOf(2), MC);
        return ask.subtract(bid, MC).multiply(BPS, MC).divide(mid, MC)
                .compareTo(BigDecimal.valueOf(maxBps)) <= 0;
    }
    private static boolean provider(String p) {
        return "UPSTOX_ANALYTICS".equals(p) || "ANGEL_ONE_SMARTAPI".equals(p);
    }
    private static boolean key(String s) {
        return s != null && s.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
    private static boolean between(int v, int low, int high) {
        return v >= low && v <= high;
    }
    private static BigDecimal round(BigDecimal n) {
        return n == null ? null : n.setScale(4, RoundingMode.HALF_UP);
    }
    private static Decision blocked(Status status, String reason, Instant at) {
        return measured(status, reason, at, null, null, null, 0, 0, 0, null);
    }
    private static Decision measured(Status status, String reason, Instant at,
                                     BigDecimal options, BigDecimal current,
                                     BigDecimal residual, int currentLots,
                                     int targetLots, int change, BigDecimal cost) {
        return new Decision(status, reason, STRATEGY_ID, at, round(options), round(current),
                round(residual), currentLots, targetLots, change, round(cost), true);
    }
}
