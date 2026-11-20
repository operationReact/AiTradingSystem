package in.aitradingsystem.tradingrooms;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * STR-175: cash-futures feasibility research, distinct from STR-170 basis pricing.
 * Only a source-verified cash-equity BUY + matching single-stock futures SELL
 * is modeled. Never represents risk-free arbitrage, a fill, or trading consent.
 * STR-170 owns price/carry calculation; this layer tests capital, L1 depth,
 * physical settlement, margin stress and incremental charge uncertainty.
 */
public final class FnoCashFuturesArbitrageResearchV1 {
    public static final String STRATEGY_ID = "strategy.STR-175";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final Duration MAX_AGE = Duration.ofSeconds(15);
    private static final BigDecimal MAX_MONEY = new BigDecimal("1000000000000");

    private FnoCashFuturesArbitrageResearchV1() {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, INFEASIBLE, NO_EDGE, RESEARCH_CANDIDATE }

    /** Verified displayed depth on ASK for spot and BID for futures, in underlying shares. */
    public record Leg(String instrumentKey, String source,
                      FnoFuturesBasisCarryV1.Quote quote, long availableUnits,
                      boolean provenanceVerified) {}

    /** Broker/settlement-verified estimates in INR; positive stress reserves are required. */
    public record Capital(BigDecimal availableCashInr, BigDecimal futureInitialMarginInr,
                          BigDecimal stressedVariationMarginInr, BigDecimal deliveryBufferInr,
                          boolean marginVerified, boolean deliveryVerified,
                          boolean settlementFundingVerified) {}

    /** Incremental to STR-170 modeled costs: never double-count the same fee. */
    public record AdditionalCosts(BigDecimal slippagePerShareInr,
                                  BigDecimal incrementalTaxFeesPerLotInr,
                                  BigDecimal dividendUncertaintyPerLotInr,
                                  BigDecimal corporateActionBufferPerLotInr,
                                  BigDecimal minStressedNetPerLotInr,
                                  boolean distinctFromBasisCostsVerified,
                                  boolean statutoryFeesVerified,
                                  boolean dividendEventsVerified,
                                  boolean corporateActionsVerified) {}

    public record Decision(Status status, String reasonCode, String strategyId, Instant asOf,
                           String spotKey, String futureKey, String source,
                           BigDecimal requiredCapitalInr,
                           BigDecimal basisNetPerLotInr,
                           BigDecimal incrementalDeductionsPerLotInr,
                           BigDecimal stressedNetPerLotInr,
                           BigDecimal returnOnRequiredCapitalBps) {
        public boolean executable() { return false; }
        public boolean orderPlaced() { return false; }
        public boolean riskFreeArbitrageVerified() { return false; }
    }

    public static Decision evaluate(FnoFuturesBasisCarryV1.Contract contract,
                                    Leg spot, Leg future,
                                    FnoFuturesBasisCarryV1.SourceRights rights,
                                    FnoFuturesBasisCarryV1.Assumptions basisAssumptions,
                                    Capital capital, AdditionalCosts costs, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (rights == null || !rights.spotUseVerified() || !rights.futuresUseVerified()) {
            return blocked(Status.BLOCKED_RIGHTS, "CASH_FUT_SOURCE_RIGHTS_UNVERIFIED", asOf);
        }
        if (contract == null || spot == null || future == null
                || !validLeg(spot, asOf) || !validLeg(future, asOf)
                || !spot.instrumentKey().equals(contract.spotKey())
                || !future.instrumentKey().equals(contract.futureKey())
                || !spot.source().equals(future.source())) {
            return blocked(Status.BLOCKED_DATA, "CASH_FUT_MATCHED_SOURCE_OR_DEPTH_INVALID", asOf);
        }

        FnoFuturesBasisCarryV1.Decision basis = FnoFuturesBasisCarryV1.evaluate(
            contract, spot.quote(), future.quote(), rights, basisAssumptions, asOf);
        if (basis.status() == FnoFuturesBasisCarryV1.Status.BLOCKED_RIGHTS) {
            return blocked(Status.BLOCKED_RIGHTS, "CASH_FUT_BASIS_RIGHTS_UNVERIFIED", asOf);
        }
        if (basis.status() == FnoFuturesBasisCarryV1.Status.BLOCKED_DATA) {
            return blocked(Status.BLOCKED_DATA, "CASH_FUT_BASIS_DATA_INVALID", asOf);
        }
        if (basis.status() == FnoFuturesBasisCarryV1.Status.NO_EDGE) {
            return blocked(Status.NO_EDGE, "CASH_FUT_BASIS_EDGE_NOT_PRESENT", asOf);
        }
        // Overnight stock borrowing / reverse carry are not assumed executable.
        if (basis.direction() != FnoFuturesBasisCarryV1.Direction.CASH_AND_CARRY) {
            return blocked(Status.INFEASIBLE, "CASH_FUT_REVERSE_BORROW_NOT_MODELED", asOf);
        }
        if (!validCapital(capital) || !validCosts(costs)) {
            return blocked(Status.BLOCKED_DATA, "CASH_FUT_CAPITAL_OR_COST_PROOF_MISSING", asOf);
        }
        if (spot.availableUnits() < contract.lotSize()
                || future.availableUnits() < contract.lotSize()) {
            return new Decision(Status.INFEASIBLE, "CASH_FUT_TWO_LEG_DEPTH_INSUFFICIENT",
                STRATEGY_ID, asOf, contract.spotKey(), contract.futureKey(),
                spot.source(), null, basis.illustrativeNetPerLotInr(),
                null, null, null);
        }

        BigDecimal lot = BigDecimal.valueOf(contract.lotSize());
        BigDecimal required = spot.quote().ask().multiply(lot, MC)
            .add(capital.futureInitialMarginInr(), MC)
            .add(capital.stressedVariationMarginInr(), MC)
            .add(capital.deliveryBufferInr(), MC);
        if (required.signum() <= 0 || required.compareTo(MAX_MONEY) > 0) {
            return blocked(Status.BLOCKED_DATA, "CASH_FUT_REQUIRED_CAPITAL_INVALID", asOf);
        }
        if (capital.availableCashInr().compareTo(required) < 0) {
            return new Decision(Status.INFEASIBLE, "CASH_FUT_AVAILABLE_CAPITAL_INSUFFICIENT",
                STRATEGY_ID, asOf, contract.spotKey(), contract.futureKey(),
                spot.source(), money(required), basis.illustrativeNetPerLotInr(),
                null, null, null);
        }

        BigDecimal extra = costs.slippagePerShareInr().multiply(lot, MC)
            .add(costs.incrementalTaxFeesPerLotInr(), MC)
            .add(costs.dividendUncertaintyPerLotInr(), MC)
            .add(costs.corporateActionBufferPerLotInr(), MC);
        BigDecimal net = basis.illustrativeNetPerLotInr().subtract(extra, MC);
        BigDecimal roi = net.multiply(new BigDecimal("10000"), MC).divide(required, MC);
        Status status = net.compareTo(costs.minStressedNetPerLotInr()) > 0
            ? Status.RESEARCH_CANDIDATE : Status.NO_EDGE;
        return new Decision(status, status == Status.RESEARCH_CANDIDATE
                ? "STRESSED_CASH_FUT_RESEARCH_CANDIDATE"
                : "CASH_FUT_STRESSED_EDGE_BELOW_HURDLE",
            STRATEGY_ID, asOf, contract.spotKey(), contract.futureKey(), spot.source(),
            money(required), basis.illustrativeNetPerLotInr(), money(extra),
            money(net), roi.setScale(4, RoundingMode.HALF_UP));
    }

    private static boolean validLeg(Leg x, Instant asOf) {
        return x != null && x.provenanceVerified() && x.instrumentKey() != null
            && ("UPSTOX_V3".equals(x.source()) || "ANGEL_ONE_SMARTAPI".equals(x.source()))
            && x.quote() != null && x.availableUnits() >= 0
            && x.availableUnits() <= 1_000_000_000L
            && x.quote().exchangeAt() != null && x.quote().observedAt() != null
            && !x.quote().exchangeAt().isAfter(x.quote().observedAt())
            && !x.quote().observedAt().isAfter(asOf)
            && Duration.between(x.quote().exchangeAt(), asOf).compareTo(MAX_AGE) <= 0;
    }

    private static boolean validCapital(Capital c) {
        return c != null && c.marginVerified() && c.deliveryVerified()
            && c.settlementFundingVerified() && bounded(c.availableCashInr(), false)
            && bounded(c.futureInitialMarginInr(), true)
            && bounded(c.stressedVariationMarginInr(), true)
            && bounded(c.deliveryBufferInr(), true);
    }

    private static boolean validCosts(AdditionalCosts x) {
        return x != null && x.distinctFromBasisCostsVerified()
            && x.statutoryFeesVerified() && x.dividendEventsVerified()
            && x.corporateActionsVerified()
            && bounded(x.slippagePerShareInr(), false)
            && bounded(x.incrementalTaxFeesPerLotInr(), false)
            && bounded(x.dividendUncertaintyPerLotInr(), false)
            && bounded(x.corporateActionBufferPerLotInr(), false)
            && bounded(x.minStressedNetPerLotInr(), true);
    }

    private static boolean bounded(BigDecimal v, boolean positive) {
        return v != null && (positive ? v.signum() > 0 : v.signum() >= 0)
            && v.compareTo(MAX_MONEY) <= 0;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static Decision blocked(Status s, String reason, Instant asOf) {
        return new Decision(s, reason, STRATEGY_ID, asOf,
            null, null, null, null, null, null, null, null);
    }
}
