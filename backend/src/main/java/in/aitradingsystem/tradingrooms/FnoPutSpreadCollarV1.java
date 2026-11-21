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
 * STR-207 (38/43) -- NON-EXECUTABLE put-spread collar expiry-payoff research.
 * A BROKER-CONFIRMED LONG NIFTY FUTURE is covered by a hypothetical BUY
 * higher-strike PUT / SELL lower-strike PUT / SELL OTM CALL, with the same
 * authentic listed NIFTY underlying, expiry and official lot size.
 *
 * Option and futures expiry settlement is only a theoretical common-index
 * payoff assumption. No actual fills, broker margin, funding or path-dependent
 * liquidation risk is included. Protection ends BELOW the lower bought-spread
 * put strike; this is not a fully protected put or defined-risk strategy.
 */
public final class FnoPutSpreadCollarV1 {
    private FnoPutSpreadCollarV1() {}
    public static final String STRATEGY_ID = "strategy.STR-207";
    private static final String INDEX = FnoLongOptionCrossoverV1.UNDERLYING;
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = BigDecimal.valueOf(10000);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN = LocalTime.of(9,15);
    private static final LocalTime CLOSE = LocalTime.of(15,30);

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_STRUCTURE, RESEARCH_CANDIDATE }
    public record Rights(boolean brokerFuturesPositionResearch,
                         boolean licensedNiftyIndexHistory,
                         boolean licensedListedOptionChain,
                         boolean licensedOriginalGreeks,
                         boolean licensedFuturesMarketQuote,
                         boolean licensedInstrumentMaster) {}
    public record FutureContract(String key, String underlying, BigDecimal tick,
                                 LocalDate listedFrom, LocalDate expiry,
                                 int officialLot, boolean sourceListed) {}
    public record BrokerFuturePosition(String key, int verifiedLongLots,
                                       BigDecimal brokerAverageEntryPriceInr,
                                       Instant brokerFirstKnownAt, boolean brokerVerified) {}
    public record OptionContract(String key, String underlying, String optionType,
                                 BigDecimal strike, BigDecimal tick, LocalDate listedFrom,
                                 LocalDate expiry, int officialLot, boolean sourceListed) {}
    public record Quote(String provider, String key, BigDecimal bid, BigDecimal ask,
                        BigDecimal originalDelta, BigDecimal originalIvAnnualizedPercent,
                        long originalOpenInterestLots, long originalBidDepthLots,
                        long originalAskDepthLots, Instant exchangeAt,
                        Instant firstKnownAt, boolean sourceVerified) {}
    public record CompletedClose(LocalDate session, String provider,
                                 BigDecimal close, Instant exchangeCloseAt,
                                 Instant firstKnownAt, boolean sourceVerified) {}
    public record Snapshot(String provider, String indexKey, String futureKey,
                           BigDecimal sourceIndexSpot, BigDecimal futureBid,
                           BigDecimal futureAsk, Quote buyHigherPut,
                           Quote sellLowerPut, Quote sellHigherCall,
                           Instant exchangeAt, Instant firstKnownAt,
                           boolean sourceVerified) {}
    /** All policy amounts are intended for strict research gating only. */
    public record Policy(int maxQuoteAgeSeconds, int maxSkewSeconds,
                         int maxOptionSpreadBps, int maxFutureSpreadBps,
                         long minimumOpenInterestLots, int maxAnnualizedIvPercent,
                         int maximumCoveredLots, int buySlippageBps,
                         int sellSlippageBps, BigDecimal perStructureFeeInr,
                         BigDecimal maximumNetDebitInr, int minimumPutWingWidthPoints) {}
    /**
     * Theoretical INR payoff at all legs' matching cash-settlement expiry,
     * conditional on an identical NIFTY index settlement; NOT a real P&L.
     */
    public record Decision(Status status, String reasonCode, String strategyId,
                           Instant asOf, int originalCompletedCloses,
                           int brokerConfirmedLongFutureLots,
                           int hypotheticalOptionStructureLots,
                           BigDecimal hypotheticalNetOptionDebitInr,
                           BigDecimal hypotheticalPutSpreadProtectionWidthInr,
                           BigDecimal hypotheticalPayoffAtIndexZeroInr,
                           BigDecimal hypotheticalPayoffAtLowerPutStrikeInr,
                           BigDecimal hypotheticalPayoffAtHigherPutStrikeInr,
                           BigDecimal hypotheticalUpsideCapAtCallStrikeInr,
                           boolean expiryScenarioOnly) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean paperTradeAuthorized() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(BrokerFuturePosition position, FutureContract future,
                                    OptionContract higherPut, OptionContract lowerPut,
                                    OptionContract shortCall, Snapshot snapshot,
                                    List<CompletedClose> history, Rights rights,
                                    Policy policy, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.brokerFuturesPositionResearch()
                || !rights.licensedNiftyIndexHistory()
                || !rights.licensedListedOptionChain() || !rights.licensedOriginalGreeks()
                || !rights.licensedFuturesMarketQuote() || !rights.licensedInstrumentMaster())
            return blocked(Status.BLOCKED_RIGHTS, "COLLAR_SOURCE_RIGHTS_UNVERIFIED", asOf);
        if (!validPolicy(policy) || !validFutureAndPosition(future, position, asOf, policy)
                || !validOptionStructure(higherPut, lowerPut, shortCall, future, snapshot, asOf, policy)
                || !validSnapshot(snapshot, future, policy, asOf)
                || position.brokerFirstKnownAt().isAfter(snapshot.exchangeAt())
                || !validHistory(history, snapshot))
            return blocked(Status.BLOCKED_DATA, "COLLAR_SOURCE_OR_CONTRACT_INVALID", asOf);
        if (!validLeg(snapshot.buyHigherPut(), higherPut, "BUY", snapshot, policy, asOf, position.verifiedLongLots())
                || !validLeg(snapshot.sellLowerPut(), lowerPut, "SELL", snapshot, policy, asOf, position.verifiedLongLots())
                || !validLeg(snapshot.sellHigherCall(), shortCall, "SELL", snapshot, policy, asOf, position.verifiedLongLots()))
            return blocked(Status.BLOCKED_DATA, "COLLAR_ORIGINAL_GREEKS_OR_DEPTH_INVALID", asOf);

        int lots = position.verifiedLongLots(); // Exactly one 1:-1:-1 per held long futures lot.
        BigDecimal units = BigDecimal.valueOf((long) lots * future.officialLot());
        BigDecimal buyPrice = effectiveBuy(snapshot.buyHigherPut().ask(), policy.buySlippageBps());
        BigDecimal lowerSale = effectiveSell(snapshot.sellLowerPut().bid(), policy.sellSlippageBps());
        BigDecimal callSale = effectiveSell(snapshot.sellHigherCall().bid(), policy.sellSlippageBps());
        // Debit may be negative (hypothetical credit), never assume "zero cost".
        BigDecimal netDebit = buyPrice.subtract(lowerSale, MC).subtract(callSale, MC)
                .multiply(units, MC).add(policy.perStructureFeeInr(), MC);
        if (netDebit.compareTo(policy.maximumNetDebitInr()) > 0)
            return measured(Status.NO_STRUCTURE, "COLLAR_NET_DEBIT_OVER_BUDGET",
                    asOf, history.size(), lots, 0, netDebit, null,null,null,null,null);

        BigDecimal wing = higherPut.strike().subtract(lowerPut.strike(), MC)
                .multiply(units, MC);
        BigDecimal zero = expiryPayoff(BigDecimal.ZERO, position, higherPut, lowerPut,
                shortCall, units, netDebit);
        BigDecimal low = expiryPayoff(lowerPut.strike(), position, higherPut, lowerPut,
                shortCall, units, netDebit);
        BigDecimal high = expiryPayoff(higherPut.strike(), position, higherPut, lowerPut,
                shortCall, units, netDebit);
        BigDecimal cap = expiryPayoff(shortCall.strike(), position, higherPut, lowerPut,
                shortCall, units, netDebit);
        // Below lower put strike, further index declines STILL lose money.
        if (zero.compareTo(low) > 0 || high.compareTo(cap) > 0)
            return blocked(Status.BLOCKED_DATA, "COLLAR_EXPIRY_PAYOFF_INVARIANT_FAILED", asOf);

        return measured(Status.RESEARCH_CANDIDATE,
                "COLLAR_HYPOTHETICAL_MATCHED_EXPIRY_ONLY", asOf, history.size(),
                lots, lots, netDebit, wing, zero, low, high, cap);
    }

    private static BigDecimal expiryPayoff(BigDecimal settlement, BrokerFuturePosition pos,
                                           OptionContract highPut, OptionContract lowPut,
                                           OptionContract shortCall, BigDecimal units,
                                           BigDecimal netDebit) {
        BigDecimal future = settlement.subtract(pos.brokerAverageEntryPriceInr(), MC);
        BigDecimal putSpread = highPut.strike().subtract(settlement, MC).max(BigDecimal.ZERO)
                .subtract(lowPut.strike().subtract(settlement, MC).max(BigDecimal.ZERO), MC);
        BigDecimal callWrite = settlement.subtract(shortCall.strike(), MC).max(BigDecimal.ZERO);
        return future.add(putSpread, MC).subtract(callWrite, MC)
                .multiply(units, MC).subtract(netDebit, MC);
    }
    private static boolean validFutureAndPosition(FutureContract f, BrokerFuturePosition p,
                                                  Instant now, Policy policy) {
        if (f == null || !f.sourceListed() || !key(f.key())
                || !INDEX.equals(f.underlying()) || !positive(f.tick())
                || f.officialLot() <= 0 || f.listedFrom() == null || f.expiry() == null
                || p == null || !p.brokerVerified() || !f.key().equals(p.key())
                || !positive(p.brokerAverageEntryPriceInr())
                || p.brokerFirstKnownAt() == null || p.brokerFirstKnownAt().isAfter(now)
                || p.verifiedLongLots() < 1 || p.verifiedLongLots() > policy.maximumCoveredLots())
            return false;
        LocalDate day = now.atZone(IST).toLocalDate();
        long dte = ChronoUnit.DAYS.between(day, f.expiry());
        return !f.listedFrom().isAfter(day) && dte >= 3 && dte <= 45;
    }
    private static boolean validOptionStructure(OptionContract higher, OptionContract lower,
                                                OptionContract call, FutureContract future,
                                                Snapshot snap, Instant at, Policy policy) {
        if (snap == null || higher == null || lower == null || call == null
                || !validOption(higher, future, "PE", at)
                || !validOption(lower, future, "PE", at)
                || !validOption(call, future, "CE", at)
                || higher.key().equals(lower.key()) || higher.key().equals(call.key())
                || lower.key().equals(call.key()) || !positive(snap.sourceIndexSpot()))
            return false;
        // Put spread collar: long higher OTM PE, short lower OTM PE, short OTM CE.
        return lower.strike().compareTo(higher.strike()) < 0
                && higher.strike().subtract(lower.strike(),MC).compareTo(
                      BigDecimal.valueOf(policy.minimumPutWingWidthPoints())) >= 0
                && higher.strike().compareTo(snap.sourceIndexSpot()) < 0
                && snap.sourceIndexSpot().compareTo(call.strike()) < 0;
    }
    private static boolean validOption(OptionContract c, FutureContract f,
                                       String type, Instant at) {
        if (!c.sourceListed() || !key(c.key()) || !INDEX.equals(c.underlying())
                || !type.equals(c.optionType()) || !positive(c.strike())
                || !positive(c.tick()) || c.strike().remainder(c.tick()).signum() != 0
                || !Objects.equals(c.expiry(), f.expiry())
                || c.officialLot() != f.officialLot() || c.listedFrom() == null
                || c.listedFrom().isAfter(at.atZone(IST).toLocalDate()))
            return false;
        return true;
    }
    private static boolean validSnapshot(Snapshot s, FutureContract f,
                                         Policy p, Instant now) {
        if (s == null || !s.sourceVerified() || !provider(s.provider())
                || !INDEX.equals(s.indexKey()) || !f.key().equals(s.futureKey())
                || !positive(s.sourceIndexSpot()) || s.exchangeAt() == null
                || s.firstKnownAt() == null || s.firstKnownAt().isBefore(s.exchangeAt())
                || s.firstKnownAt().isAfter(now)
                || Duration.between(s.exchangeAt(), now).compareTo(
                        Duration.ofSeconds(p.maxQuoteAgeSeconds())) > 0
                || !spread(s.futureBid(), s.futureAsk(), p.maxFutureSpreadBps()))
            return false;
        var z = s.exchangeAt().atZone(IST);
        return z.getDayOfWeek().getValue() <= 5 && !z.toLocalTime().isBefore(OPEN)
                && !z.toLocalTime().isAfter(CLOSE);
    }
    private static boolean validLeg(Quote q, OptionContract c, String side,
                                    Snapshot s, Policy p, Instant now, int requiredLots) {
        if (q == null || !q.sourceVerified() || !s.provider().equals(q.provider())
                || !c.key().equals(q.key()) || !spread(q.bid(),q.ask(),p.maxOptionSpreadBps())
                || !positive(q.originalIvAnnualizedPercent())
                || q.originalIvAnnualizedPercent().compareTo(
                        BigDecimal.valueOf(p.maxAnnualizedIvPercent())) > 0
                || q.originalOpenInterestLots() < p.minimumOpenInterestLots()
                || q.exchangeAt() == null || q.firstKnownAt() == null
                || q.firstKnownAt().isBefore(q.exchangeAt()) || q.firstKnownAt().isAfter(now)
                || q.firstKnownAt().isAfter(s.firstKnownAt())
                || Duration.between(q.exchangeAt(), now).compareTo(
                        Duration.ofSeconds(p.maxQuoteAgeSeconds())) > 0
                || Math.abs(Duration.between(q.exchangeAt(),s.exchangeAt()).getSeconds())
                         > p.maxSkewSeconds()
                || q.originalDelta() == null)
            return false;
        if ("BUY".equals(side) && q.originalAskDepthLots() < requiredLots)
            return false;
        if ("SELL".equals(side) && q.originalBidDepthLots() < requiredLots)
            return false;
        BigDecimal d = q.originalDelta();
        return "CE".equals(c.optionType())
                ? d.signum() > 0 && d.compareTo(BigDecimal.ONE) < 0
                : d.signum() < 0 && d.compareTo(BigDecimal.ONE.negate()) > 0;
    }
    private static boolean validHistory(List<CompletedClose> bars, Snapshot s) {
        if (bars == null || bars.size() < 35 || bars.size() > 512)
            return false;
        LocalDate previous = null;
        Set<LocalDate> seen = new HashSet<>();
        for (CompletedClose b : bars) {
            if (b == null || !b.sourceVerified() || b.session() == null
                    || b.session().getDayOfWeek().getValue() > 5
                    || !seen.add(b.session()) || !s.provider().equals(b.provider())
                    || !positive(b.close()) || b.exchangeCloseAt() == null
                    || b.firstKnownAt() == null) return false;
            Instant close = b.session().atTime(CLOSE).atZone(IST).toInstant();
            if (!close.equals(b.exchangeCloseAt()) || b.firstKnownAt().isBefore(close)
                    || b.firstKnownAt().isAfter(close.plus(Duration.ofHours(36)))
                    || !b.firstKnownAt().isBefore(s.exchangeAt())
                    || previous != null && (ChronoUnit.DAYS.between(previous,b.session()) < 1
                     || ChronoUnit.DAYS.between(previous,b.session()) > 8))
                return false;
            previous = b.session();
        }
        long lag = ChronoUnit.DAYS.between(previous,s.exchangeAt().atZone(IST).toLocalDate());
        return lag >= 1 && lag <= 5;
    }
    private static boolean validPolicy(Policy p) {
        return p != null && between(p.maxQuoteAgeSeconds(),30,600)
                && between(p.maxSkewSeconds(),0,120)
                && between(p.maxOptionSpreadBps(),1,5000)
                && between(p.maxFutureSpreadBps(),1,2000)
                && p.minimumOpenInterestLots() >= 1
                && between(p.maxAnnualizedIvPercent(),30,500)
                && between(p.maximumCoveredLots(),1,100)
                && between(p.buySlippageBps(),0,1000)
                && between(p.sellSlippageBps(),0,1000)
                && positive(p.perStructureFeeInr()) && positive(p.maximumNetDebitInr())
                && between(p.minimumPutWingWidthPoints(),1,10000);
    }
    private static BigDecimal effectiveBuy(BigDecimal ask,int bps) {
        return ask.multiply(BPS.add(BigDecimal.valueOf(bps),MC).divide(BPS,MC),MC);
    }
    private static BigDecimal effectiveSell(BigDecimal bid,int bps) {
        return bid.multiply(BPS.subtract(BigDecimal.valueOf(bps),MC).divide(BPS,MC),MC);
    }
    private static boolean spread(BigDecimal bid,BigDecimal ask,int maxBps) {
        if (!positive(bid) || !positive(ask) || ask.compareTo(bid) < 0) return false;
        BigDecimal mid = bid.add(ask,MC).divide(BigDecimal.valueOf(2),MC);
        return ask.subtract(bid,MC).multiply(BPS,MC).divide(mid,MC)
                .compareTo(BigDecimal.valueOf(maxBps)) <= 0;
    }
    private static boolean provider(String s) {
        return "UPSTOX_ANALYTICS".equals(s) || "ANGEL_ONE_SMARTAPI".equals(s);
    }
    private static boolean key(String s) {
        return s != null && s.startsWith("NSE_FO|")
                && s.substring(7).matches("[A-Za-z0-9._-]{1,64}");
    }
    private static boolean positive(BigDecimal v) { return v != null && v.signum()>0; }
    private static boolean between(int n,int lo,int hi) { return n>=lo && n<=hi; }
    private static BigDecimal rounded(BigDecimal x) {
        return x == null ? null : x.setScale(4,RoundingMode.HALF_UP);
    }
    private static Decision blocked(Status status,String reason,Instant at) {
        return measured(status,reason,at,0,0,0,null,null,null,null,null,null);
    }
    private static Decision measured(Status status,String reason,Instant at,
                                     int closes,int confirmedLots,int optionLots,
                                     BigDecimal debit,BigDecimal wing,BigDecimal zero,
                                     BigDecimal low,BigDecimal high,BigDecimal cap) {
        return new Decision(status,reason,STRATEGY_ID,at,closes,confirmedLots,
                optionLots,rounded(debit),rounded(wing),rounded(zero),
                rounded(low),rounded(high),rounded(cap),true);
    }
}
