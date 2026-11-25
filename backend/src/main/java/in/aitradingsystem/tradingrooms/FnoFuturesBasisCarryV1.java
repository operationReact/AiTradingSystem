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
 * STR-170 — deterministic, research-only single-stock futures basis carry.
 *
 * <p>Computes an indicative cash-and-carry or reverse-carry gap using independently
 * validated, exchange-timed spot/future bid/ask quotes for the SAME stock.
 * A research candidate is not arbitrage, an executable order, a verified profit,
 * or permission to display/retain market data. No broker client or order API exists here.
 * Index futures are deliberately excluded: a quoted NIFTY index is not a
 * directly deliverable spot leg. Only a separately modeled index basket could
 * support index carry.
 *
 * <p>The user-supplied rate/yield/round-trip costs are transparent assumptions,
 * not a brokerage quote, guaranteed dividend, funding offer, or historical proof.
 */
public final class FnoFuturesBasisCarryV1 {
    public static final String STRATEGY_ID = "strategy.STR-170";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final BigDecimal YEAR_DAYS = new BigDecimal("365");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration MAX_AGE = Duration.ofSeconds(15);
    private static final Duration MAX_SKEW = Duration.ofSeconds(10);

    private FnoFuturesBasisCarryV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_EDGE, RESEARCH_CANDIDATE }

    public enum Direction { NONE, CASH_AND_CARRY, REVERSE_CARRY }

    /** All prices are INR per underlying share, not lot price; exchange timestamps are authoritative. */
    public record Quote(BigDecimal bid, BigDecimal ask, Instant exchangeAt, Instant observedAt) {}

    /** Matching, provider-verified NSE single-stock futures identity. */
    public record Contract(String spotKey, String futureKey, String futureUnderlyingSpotKey,
                           LocalDate expiry, int lotSize, boolean contractVerified,
                           boolean deliverableSpotVerified) {}

    /** Explicit permissions are supplied by an authorized server-side source gate, not a client. */
    public record SourceRights(boolean spotUseVerified, boolean futuresUseVerified,
                               boolean exchangeSessionVerified, boolean stockBorrowVerified) {}

    /** Bps are annual rates except roundTripCostsBps and minNetEdgeBps. */
    public record Assumptions(BigDecimal fundingAnnualBps, BigDecimal dividendAnnualBps,
                              BigDecimal stockBorrowAnnualBps, BigDecimal roundTripCostsBps,
                              BigDecimal minNetEdgeBps) {}

    /** No record field is a broker order or permission to trade. */
    public record Decision(Status status, Direction direction, String reasonCode,
                           String strategyId, Instant asOf, LocalDate expiry,
                           BigDecimal cashCarryNetBps, BigDecimal reverseCarryNetBps,
                           BigDecimal selectedNetBps, BigDecimal illustrativeNetPerLotInr) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
    }

    /**
     * Strictly as-of research. No future quotes, stale prices, index-spot substitution,
     * unverified source rights, unverified borrow/contract, or negative fee assumptions.
     */
    public static Decision evaluate(Contract contract, Quote spot, Quote future,
                                    SourceRights rights, Assumptions assumptions, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.spotUseVerified() || !rights.futuresUseVerified())
            return blocked(Status.BLOCKED_RIGHTS, "FNO_SOURCE_USE_RIGHTS_NOT_VERIFIED", asOf);
        if (!rights.exchangeSessionVerified())
            return blocked(Status.BLOCKED_DATA, "EXCHANGE_SESSION_NOT_VERIFIED", asOf);
        if (!validContract(contract, asOf))
            return blocked(Status.BLOCKED_DATA, "FUTURES_CONTRACT_OR_SPOT_IDENTITY_INVALID", asOf);
        if (!validQuote(spot, asOf) || !validQuote(future, asOf))
            return blocked(Status.BLOCKED_DATA, "FNO_QUOTE_INVALID_OR_STALE", asOf);
        if (Duration.between(spot.exchangeAt(), future.exchangeAt()).abs().compareTo(MAX_SKEW) > 0)
            return blocked(Status.BLOCKED_DATA, "SPOT_FUTURES_EXCHANGE_TIME_SKEW", asOf);
        if (!validAssumptions(assumptions))
            return blocked(Status.BLOCKED_DATA, "FUTURES_CARRY_COST_ASSUMPTIONS_INVALID", asOf);

        long days = ChronoUnit.DAYS.between(asOf.atZone(IST).toLocalDate(), contract.expiry());
        BigDecimal fraction = BigDecimal.valueOf(days).divide(YEAR_DAYS, MC);
        BigDecimal fundingNet = assumptions.fundingAnnualBps()
            .subtract(assumptions.dividendAnnualBps(), MC).divide(BPS, MC);
        BigDecimal borrow = assumptions.stockBorrowAnnualBps().divide(BPS, MC);
        BigDecimal costs = spot.ask().multiply(assumptions.roundTripCostsBps(), MC)
            .divide(BPS, MC);

        // Cash-and-carry: buy stock at ASK; short the future at BID. Carry cash
        // financing minus estimated dividend benefits through expiry.
        BigDecimal cashGross = future.bid().subtract(
            spot.ask().multiply(BigDecimal.ONE.add(fundingNet.multiply(fraction, MC)), MC), MC);
        BigDecimal cashNet = cashGross.subtract(costs, MC);
        BigDecimal cashBps = cashNet.multiply(BPS, MC).divide(spot.ask(), MC);

        // Reverse carry requires independently verified stock borrow and delivery;
        // never assume an Indian cash-equity short can be held overnight.
        BigDecimal reverseBps = null;
        BigDecimal reverseNet = null;
        if (rights.stockBorrowVerified()) {
            BigDecimal shortCarry = fundingNet.subtract(borrow, MC);
            BigDecimal reverseGross = spot.bid().multiply(
                BigDecimal.ONE.add(shortCarry.multiply(fraction, MC)), MC)
                .subtract(future.ask(), MC);
            reverseNet = reverseGross.subtract(costs, MC);
            reverseBps = reverseNet.multiply(BPS, MC).divide(spot.ask(), MC);
        }

        BigDecimal hurdle = assumptions.minNetEdgeBps();
        boolean cashPass = cashBps.compareTo(hurdle) > 0;
        boolean reversePass = reverseBps != null && reverseBps.compareTo(hurdle) > 0;
        if (!cashPass && !reversePass) {
            return new Decision(Status.NO_EDGE, Direction.NONE, "CARRY_EDGE_BELOW_COST_AND_HURDLE",
                STRATEGY_ID, asOf, contract.expiry(), decimal(cashBps), decimal(reverseBps),
                null, null);
        }
        boolean selectCash = cashPass && (!reversePass || cashBps.compareTo(reverseBps) >= 0);
        BigDecimal selectedNet = selectCash ? cashNet : reverseNet;
        BigDecimal selectedBps = selectCash ? cashBps : reverseBps;
        return new Decision(Status.RESEARCH_CANDIDATE,
            selectCash ? Direction.CASH_AND_CARRY : Direction.REVERSE_CARRY,
            selectCash ? "NET_CASH_CARRY_RESEARCH_EDGE" : "NET_REVERSE_CARRY_RESEARCH_EDGE",
            STRATEGY_ID, asOf, contract.expiry(), decimal(cashBps), decimal(reverseBps),
            decimal(selectedBps), selectedNet.multiply(BigDecimal.valueOf(contract.lotSize()), MC)
                .setScale(2, RoundingMode.HALF_UP));
    }

    private static BigDecimal decimal(BigDecimal n) {
        return n == null ? null : n.setScale(4, RoundingMode.HALF_UP);
    }

    private static boolean validContract(Contract contract, Instant asOf) {
        if (contract == null || !contract.contractVerified() || !contract.deliverableSpotVerified()
                || contract.spotKey() == null || contract.futureKey() == null
                || !contract.spotKey().matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")
                || !contract.futureKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || !contract.spotKey().equals(contract.futureUnderlyingSpotKey())
                || contract.lotSize() < 1 || contract.lotSize() > 100000 || contract.expiry() == null)
            return false;
        long remaining = ChronoUnit.DAYS.between(asOf.atZone(IST).toLocalDate(), contract.expiry());
        return remaining >= 3 && remaining <= 90; // no near-expiry delivery research candidates
    }

    private static boolean validQuote(Quote q, Instant asOf) {
        return q != null && positive(q.bid()) && positive(q.ask())
            && q.ask().compareTo(q.bid()) >= 0 && q.exchangeAt() != null
            && q.observedAt() != null && !q.exchangeAt().isAfter(q.observedAt())
            && !q.observedAt().isAfter(asOf) && !q.exchangeAt().isAfter(asOf)
            && Duration.between(q.exchangeAt(), asOf).compareTo(MAX_AGE) <= 0
            && Duration.between(q.observedAt(), asOf).compareTo(MAX_AGE) <= 0;
    }

    private static boolean positive(BigDecimal x) { return x != null && x.signum() > 0; }

    private static boolean bounded(BigDecimal x, int min, int max) {
        return x != null && x.compareTo(BigDecimal.valueOf(min)) >= 0
            && x.compareTo(BigDecimal.valueOf(max)) <= 0;
    }

    private static boolean validAssumptions(Assumptions a) {
        return a != null && bounded(a.fundingAnnualBps(), 0, 5000)
            && bounded(a.dividendAnnualBps(), 0, 10000)
            && bounded(a.stockBorrowAnnualBps(), 0, 10000)
            && bounded(a.roundTripCostsBps(), 1, 2000)
            && bounded(a.minNetEdgeBps(), 1, 2000);
    }

    private static Decision blocked(Status status, String reason, Instant now) {
        return new Decision(status, Direction.NONE, reason, STRATEGY_ID, now,
            null, null, null, null, null);
    }
}
