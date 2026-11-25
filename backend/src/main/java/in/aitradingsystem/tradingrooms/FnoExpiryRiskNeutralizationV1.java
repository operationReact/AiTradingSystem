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
 * STR-209 (40/43): research-only hypothetical closing of an entire,
 * BROKER-ATTESTED, originally known NIFTY options expiry book BEFORE an
 * officially evidenced last-tradable cutoff. No broker orders are ever sent.
 *
 * "Neutralization" means modeled full close of the separately attested expiry
 * book ONLY. It does not hedge other portfolios, guarantee a fill, eliminate
 * margin calls, or prove that a real portfolio is risk-neutral.
 */
public final class FnoExpiryRiskNeutralizationV1 {
    public static final String STRATEGY_ID = "strategy.STR-209";
    public static final String INDEX = "NSE_INDEX|Nifty 50";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN = LocalTime.of(9,15);
    private static final LocalTime CLOSE = LocalTime.of(15,30);
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = BigDecimal.valueOf(10000);
    private static final BigDecimal TWO_TENTHS = new BigDecimal("0.2");

    private FnoExpiryRiskNeutralizationV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, OUTSIDE_CUTOFF_WINDOW, NO_ACTION,
                         RESEARCH_CLOSE_ALL }
    public record Rights(boolean brokerCompleteExpiryBookResearch,
                         boolean licensedNiftyIndexHistory,
                         boolean licensedOriginalOptionChain,
                         boolean licensedOriginalGreeks,
                         boolean licensedOfficialInstrumentMaster,
                         boolean licensedOfficialExpirySchedule) {}
    /** Caller must attest completeness for one expiry; no hidden positions. */
    public record BrokerBook(boolean sourceVerifiedCompleteExpiryBook,
                             Instant brokerBookFirstKnownAt, List<Position> positions) {}
    public record Contract(String key, String index, String type, BigDecimal strike,
                           BigDecimal tick, LocalDate listedFrom, LocalDate expiry,
                           int officialLot, boolean sourceListed) {}
    public record Position(Contract contract, int signedBrokerLots,
                           Instant brokerFirstKnownAt, boolean brokerVerified) {}
    /** Original delta per underlying unit and gamma per underlying point. */
    public record Quote(String key, String provider, BigDecimal bid, BigDecimal ask,
                        BigDecimal originalDelta, BigDecimal originalGamma,
                        BigDecimal originalAnnualizedIvPercent, long openInterestLots,
                        long bidDepthLots, long askDepthLots,
                        Instant exchangeAt, Instant firstKnownAt, boolean sourceVerified) {}
    public record CompletedClose(LocalDate session, String provider,
                                 BigDecimal close, Instant exchangeCloseAt,
                                 Instant firstKnownAt, boolean sourceVerified) {}
    /**
     * Must originate from verified exchange calendar + instrument lifecycle.
     * No hardcoded weekly expiry weekday or invented cutoff.
     */
    public record ExpiryEvidence(String provider, String indexKey, LocalDate expiryDate,
                                 Instant officialLastTradableAt, Instant firstKnownAt,
                                 boolean sourceVerified) {}
    public record Snapshot(String provider, String indexKey, BigDecimal sourceIndexSpot,
                           List<Quote> optionQuotes, Instant exchangeAt,
                           Instant firstKnownAt, boolean sourceVerified) {}
    public record Policy(int maxQuoteAgeSeconds, int maxSkewSeconds,
                         int minMinutesBeforeCutoff, int maxMinutesBeforeCutoff,
                         int maximumOptionSpreadBps, long minimumOpenInterestLots,
                         int maximumAnnualizedIvPercent, int maxBookContracts,
                         int maxAbsBrokerLotsPerContract, int buySlippageBps,
                         int sellSlippageBps, BigDecimal feePerClosingLegInr,
                         BigDecimal maxPositiveNetCloseDebitInr) {}
    /** Intrinsic stress amounts are expiry scenario gross payoff, NOT P&L. */
    public record Decision(Status status, String reasonCode, String strategyId,
                           Instant asOf, int minutesBeforeOfficialCutoff,
                           int authenticatedBookContracts, int totalHypotheticalClosingLots,
                           BigDecimal originalNetOptionDeltaUnits,
                           BigDecimal originalNetOptionGammaUnitsPerPoint,
                           BigDecimal closestStrikeDistanceBps,
                           BigDecimal hypotheticalNetCloseDebitInr,
                           BigDecimal grossExpiryIntrinsicAtZeroInr,
                           BigDecimal grossExpiryIntrinsicAtSpotInr,
                           BigDecimal grossExpiryIntrinsicAtSpotMinus20PercentInr,
                           BigDecimal grossExpiryIntrinsicAtSpotPlus20PercentInr,
                           boolean potentiallyUnlimitedUpsideShortCallLiability,
                           boolean fullExpiryBookOnly) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean paperTradeAuthorized() { return false; }
        public boolean liveTradeAuthorized() { return false; }
    }

    public static Decision evaluate(BrokerBook book, Snapshot snapshot,
                                    ExpiryEvidence expiry,
                                    List<CompletedClose> originalHistory,
                                    Rights rights, Policy policy, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.brokerCompleteExpiryBookResearch()
                || !rights.licensedNiftyIndexHistory()
                || !rights.licensedOriginalOptionChain()
                || !rights.licensedOriginalGreeks()
                || !rights.licensedOfficialInstrumentMaster()
                || !rights.licensedOfficialExpirySchedule())
            return blocked(Status.BLOCKED_RIGHTS, "EXPIRY_SOURCE_RIGHTS_UNVERIFIED", asOf);
        if (!validPolicy(policy) || !validBook(book, snapshot, expiry, policy, asOf)
                || !validSnapshot(snapshot, policy, asOf)
                || !validOfficialExpiry(expiry, snapshot, asOf)
                || !validHistory(originalHistory, snapshot))
            return blocked(Status.BLOCKED_DATA, "EXPIRY_BOOK_OR_SOURCE_INVALID", asOf);

        Map<String, Quote> sourceQuotes = new HashMap<>();
        for (Quote q : snapshot.optionQuotes()) {
            if (q == null || sourceQuotes.putIfAbsent(q.key(), q) != null)
                return blocked(Status.BLOCKED_DATA, "EXPIRY_DUPLICATE_OR_NULL_QUOTE", asOf);
        }
        if (sourceQuotes.size() != book.positions().size())
            return blocked(Status.BLOCKED_DATA, "EXPIRY_INCOMPLETE_OR_EXTRA_QUOTES", asOf);

        BigDecimal delta = BigDecimal.ZERO, gamma = BigDecimal.ZERO;
        BigDecimal minStrikeBps = null, netCloseDebit = BigDecimal.ZERO;
        BigDecimal atZero = BigDecimal.ZERO, atSpot = BigDecimal.ZERO;
        BigDecimal atMinus = BigDecimal.ZERO, atPlus = BigDecimal.ZERO;
        BigDecimal netHighSpotCallUnits = BigDecimal.ZERO;
        BigDecimal spot = snapshot.sourceIndexSpot();
        BigDecimal spotMinus = spot.multiply(BigDecimal.ONE.subtract(TWO_TENTHS), MC);
        BigDecimal spotPlus = spot.multiply(BigDecimal.ONE.add(TWO_TENTHS), MC);
        int totalLots = 0;
        for (Position position : book.positions()) {
            Contract c = position.contract();
            Quote q = sourceQuotes.get(c.key());
            int absLots = Math.abs(position.signedBrokerLots());
            if (!validQuote(q, c, position.signedBrokerLots(), snapshot, policy, asOf))
                return blocked(Status.BLOCKED_DATA, "EXPIRY_QUOTE_OR_CLOSING_DEPTH_INVALID", asOf);

            BigDecimal signedUnits = BigDecimal.valueOf((long) position.signedBrokerLots()
                    * c.officialLot());
            BigDecimal absUnits = BigDecimal.valueOf((long) absLots * c.officialLot());
            totalLots += absLots;
            delta = delta.add(signedUnits.multiply(q.originalDelta(), MC), MC);
            gamma = gamma.add(signedUnits.multiply(q.originalGamma(), MC), MC);
            BigDecimal distance = c.strike().subtract(spot, MC).abs()
                    .multiply(BPS, MC).divide(spot, MC);
            if (minStrikeBps == null || distance.compareTo(minStrikeBps) < 0)
                minStrikeBps = distance;

            // A long position sells to close at BID; a short buys to close at ASK.
            // Debit is positive for buys, negative for sales; fees add cost.
            BigDecimal price = position.signedBrokerLots() > 0
                    ? effectiveSell(q.bid(), policy.sellSlippageBps())
                    : effectiveBuy(q.ask(), policy.buySlippageBps());
            BigDecimal signedDebit = price.multiply(absUnits, MC)
                    .multiply(position.signedBrokerLots() > 0
                            ? BigDecimal.ONE.negate() : BigDecimal.ONE, MC);
            netCloseDebit = netCloseDebit.add(signedDebit, MC)
                    .add(policy.feePerClosingLegInr(), MC);

            atZero = atZero.add(expiryIntrinsic(c, BigDecimal.ZERO)
                    .multiply(signedUnits, MC), MC);
            atSpot = atSpot.add(expiryIntrinsic(c, spot)
                    .multiply(signedUnits, MC), MC);
            atMinus = atMinus.add(expiryIntrinsic(c, spotMinus)
                    .multiply(signedUnits, MC), MC);
            atPlus = atPlus.add(expiryIntrinsic(c, spotPlus)
                    .multiply(signedUnits, MC), MC);
            if ("CE".equals(c.type()))
                netHighSpotCallUnits = netHighSpotCallUnits.add(signedUnits, MC);
        }

        long minutes = Duration.between(asOf, expiry.officialLastTradableAt()).toMinutes();
        boolean upsideOpenEnded = netHighSpotCallUnits.signum() < 0;
        if (minutes < policy.minMinutesBeforeCutoff()
                || minutes > policy.maxMinutesBeforeCutoff()) {
            return measured(Status.OUTSIDE_CUTOFF_WINDOW, "EXPIRY_NOT_IN_VERIFIED_CLOSE_WINDOW",
                    asOf,minutes,book.positions().size(),totalLots,delta,gamma,minStrikeBps,
                    netCloseDebit,atZero,atSpot,atMinus,atPlus,upsideOpenEnded);
        }
        if (netCloseDebit.compareTo(policy.maxPositiveNetCloseDebitInr()) > 0)
            return measured(Status.NO_ACTION, "EXPIRY_CLOSE_ESTIMATED_DEBIT_OVER_BUDGET",
                    asOf,minutes,book.positions().size(),totalLots,delta,gamma,minStrikeBps,
                    netCloseDebit,atZero,atSpot,atMinus,atPlus,upsideOpenEnded);
        return measured(Status.RESEARCH_CLOSE_ALL, "EXPIRY_HYPOTHETICAL_FULL_BOOK_CLOSE_ONLY",
                asOf,minutes,book.positions().size(),totalLots,delta,gamma,minStrikeBps,
                netCloseDebit,atZero,atSpot,atMinus,atPlus,upsideOpenEnded);
    }

    private static boolean validBook(BrokerBook book, Snapshot s, ExpiryEvidence expiry,
                                     Policy policy, Instant now) {
        if (book == null || !book.sourceVerifiedCompleteExpiryBook()
                || book.brokerBookFirstKnownAt() == null || s == null || expiry == null
                || book.brokerBookFirstKnownAt().isAfter(now)
                || s.exchangeAt() == null
                || book.brokerBookFirstKnownAt().isAfter(s.exchangeAt())
                || book.positions() == null || book.positions().isEmpty()
                || book.positions().size() > policy.maxBookContracts()) return false;
        Set<String> seen = new HashSet<>();
        for (Position p : book.positions()) {
            if (p == null || !p.brokerVerified() || p.contract() == null
                    || !validContract(p.contract(), expiry, now)
                    || !seen.add(p.contract().key())
                    || p.signedBrokerLots() == 0
                    || p.signedBrokerLots() == Integer.MIN_VALUE
                    || Math.abs(p.signedBrokerLots()) > policy.maxAbsBrokerLotsPerContract()
                    || p.brokerFirstKnownAt() == null
                    || p.brokerFirstKnownAt().isAfter(book.brokerBookFirstKnownAt()))
                return false;
        }
        return true;
    }
    private static boolean validContract(Contract c, ExpiryEvidence expiry, Instant now) {
        if (!c.sourceListed() || !key(c.key()) || !INDEX.equals(c.index())
                || !("PE".equals(c.type()) || "CE".equals(c.type()))
                || !positive(c.strike()) || !positive(c.tick())
                || c.strike().remainder(c.tick()).signum() != 0
                || c.listedFrom() == null || c.listedFrom().isAfter(now.atZone(IST).toLocalDate())
                || c.expiry() == null || !c.expiry().equals(expiry.expiryDate())
                || c.officialLot() <= 0) return false;
        return true;
    }
    private static boolean validOfficialExpiry(ExpiryEvidence e, Snapshot s, Instant now) {
        if (e == null || !e.sourceVerified() || !s.provider().equals(e.provider())
                || !INDEX.equals(e.indexKey()) || e.expiryDate() == null
                || e.officialLastTradableAt() == null || e.firstKnownAt() == null
                || e.firstKnownAt().isAfter(s.firstKnownAt())
                || e.firstKnownAt().isAfter(now)) return false;
        var cutoff = e.officialLastTradableAt().atZone(IST);
        return cutoff.toLocalDate().equals(e.expiryDate())
                && cutoff.getDayOfWeek().getValue() <= 5
                && !cutoff.toLocalTime().isBefore(OPEN)
                && !cutoff.toLocalTime().isAfter(CLOSE);
    }
    private static boolean validSnapshot(Snapshot s, Policy p, Instant now) {
        if (s == null || !s.sourceVerified() || !provider(s.provider())
                || !INDEX.equals(s.indexKey()) || !positive(s.sourceIndexSpot())
                || s.exchangeAt() == null || s.firstKnownAt() == null
                || s.firstKnownAt().isBefore(s.exchangeAt())
                || s.firstKnownAt().isAfter(now)
                || Duration.between(s.exchangeAt(), now).compareTo(
                        Duration.ofSeconds(p.maxQuoteAgeSeconds())) > 0
                || s.optionQuotes() == null || s.optionQuotes().isEmpty()) return false;
        var time = s.exchangeAt().atZone(IST);
        return time.getDayOfWeek().getValue() <= 5
                && !time.toLocalTime().isBefore(OPEN)
                && !time.toLocalTime().isAfter(CLOSE);
    }
    private static boolean validQuote(Quote q, Contract c, int signedLots,
                                      Snapshot s, Policy p, Instant at) {
        if (q == null || !q.sourceVerified() || !c.key().equals(q.key())
                || !s.provider().equals(q.provider())
                || !positive(q.bid()) || !positive(q.ask()) || q.ask().compareTo(q.bid()) < 0
                || !positive(q.originalAnnualizedIvPercent())
                || q.originalAnnualizedIvPercent().compareTo(
                        BigDecimal.valueOf(p.maximumAnnualizedIvPercent())) > 0
                || q.openInterestLots() < p.minimumOpenInterestLots()
                || q.originalDelta() == null || q.originalGamma() == null
                || !positive(q.originalGamma())
                || q.exchangeAt() == null || q.firstKnownAt() == null
                || q.firstKnownAt().isBefore(q.exchangeAt())
                || q.firstKnownAt().isAfter(s.firstKnownAt())
                || q.firstKnownAt().isAfter(at)
                || Duration.between(q.exchangeAt(), at).compareTo(
                        Duration.ofSeconds(p.maxQuoteAgeSeconds())) > 0
                || Math.abs(Duration.between(q.exchangeAt(), s.exchangeAt()).toSeconds())
                        > p.maxSkewSeconds()) return false;
        BigDecimal d = q.originalDelta();
        if ("CE".equals(c.type())
                ? d.signum() <= 0 || d.compareTo(BigDecimal.ONE) >= 0
                : d.signum() >= 0 || d.compareTo(BigDecimal.ONE.negate()) <= 0) return false;
        BigDecimal mid = q.bid().add(q.ask(), MC).divide(BigDecimal.valueOf(2), MC);
        if (q.ask().subtract(q.bid(), MC).multiply(BPS, MC).divide(mid, MC)
                .compareTo(BigDecimal.valueOf(p.maximumOptionSpreadBps())) > 0) return false;
        return signedLots > 0 ? q.bidDepthLots() >= signedLots
                : q.askDepthLots() >= -(long)signedLots;
    }
    private static boolean validHistory(List<CompletedClose> closes, Snapshot s) {
        if (closes == null || closes.size() < 35 || closes.size() > 512) return false;
        LocalDate previous = null;
        Set<LocalDate> seen = new HashSet<>();
        for (CompletedClose bar : closes) {
            if (bar == null || !bar.sourceVerified() || bar.session() == null
                    || bar.session().getDayOfWeek().getValue() > 5
                    || !seen.add(bar.session()) || !s.provider().equals(bar.provider())
                    || !positive(bar.close()) || bar.exchangeCloseAt() == null
                    || bar.firstKnownAt() == null) return false;
            Instant originalClose = bar.session().atTime(CLOSE).atZone(IST).toInstant();
            if (!originalClose.equals(bar.exchangeCloseAt())
                    || bar.firstKnownAt().isBefore(originalClose)
                    || bar.firstKnownAt().isAfter(originalClose.plus(Duration.ofHours(36)))
                    || !bar.firstKnownAt().isBefore(s.exchangeAt())
                    || previous != null && (ChronoUnit.DAYS.between(previous,bar.session()) < 1
                    || ChronoUnit.DAYS.between(previous,bar.session()) > 8)) return false;
            previous = bar.session();
        }
        long days = ChronoUnit.DAYS.between(previous,s.exchangeAt().atZone(IST).toLocalDate());
        return days >= 1 && days <= 5;
    }
    private static BigDecimal expiryIntrinsic(Contract c, BigDecimal settlement) {
        return "CE".equals(c.type()) ? settlement.subtract(c.strike(), MC).max(BigDecimal.ZERO)
                : c.strike().subtract(settlement, MC).max(BigDecimal.ZERO);
    }
    private static boolean validPolicy(Policy p) {
        return p != null && between(p.maxQuoteAgeSeconds(),30,600)
                && between(p.maxSkewSeconds(),0,120)
                && between(p.minMinutesBeforeCutoff(),1,120)
                && between(p.maxMinutesBeforeCutoff(),30,720)
                && p.minMinutesBeforeCutoff() < p.maxMinutesBeforeCutoff()
                && between(p.maximumOptionSpreadBps(),1,5000)
                && p.minimumOpenInterestLots() >= 1
                && between(p.maximumAnnualizedIvPercent(),30,1000)
                && between(p.maxBookContracts(),1,30)
                && between(p.maxAbsBrokerLotsPerContract(),1,500)
                && between(p.buySlippageBps(),0,1000)
                && between(p.sellSlippageBps(),0,1000)
                && positive(p.feePerClosingLegInr())
                && positive(p.maxPositiveNetCloseDebitInr());
    }
    private static BigDecimal effectiveBuy(BigDecimal ask,int slip) {
        return ask.multiply(BPS.add(BigDecimal.valueOf(slip), MC).divide(BPS, MC), MC);
    }
    private static BigDecimal effectiveSell(BigDecimal bid,int slip) {
        return bid.multiply(BPS.subtract(BigDecimal.valueOf(slip), MC).divide(BPS, MC), MC);
    }
    private static boolean provider(String p) {
        return "UPSTOX_ANALYTICS".equals(p) || "ANGEL_ONE_SMARTAPI".equals(p);
    }
    private static boolean key(String k) {
        return k != null && k.startsWith("NSE_FO|")
                && k.substring(7).matches("[A-Za-z0-9._-]{1,64}");
    }
    private static boolean positive(BigDecimal b) { return b != null && b.signum() > 0; }
    private static boolean between(int n,int a,int b) { return n >= a && n <= b; }
    private static BigDecimal rounded(BigDecimal b) {
        return b == null ? null : b.setScale(4, RoundingMode.HALF_UP);
    }
    private static Decision blocked(Status status,String reason,Instant at) {
        return measured(status,reason,at,0,0,0,null,null,null,
                null,null,null,null,null,false);
    }
    private static Decision measured(Status status,String reason,Instant at,long minutes,
                                     int contracts,int lots,BigDecimal delta,BigDecimal gamma,
                                     BigDecimal distance,BigDecimal debit,
                                     BigDecimal zero,BigDecimal spot,BigDecimal minus,
                                     BigDecimal plus,boolean upsideRisk) {
        return new Decision(status,reason,STRATEGY_ID,at,
                Math.toIntExact(Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,minutes))),
                contracts,lots,rounded(delta),rounded(gamma),rounded(distance),
                rounded(debit),rounded(zero),rounded(spot),rounded(minus),rounded(plus),
                upsideRisk,true);
    }
}
