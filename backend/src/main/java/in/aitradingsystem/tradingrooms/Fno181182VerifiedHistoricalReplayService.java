package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore.OptionProof;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Protected, read-only historical source adapter for STR-181 CE and STR-182 PE.
 *
 * <p>The V106 source store requires independent MODEL_INFERENCE rights receipts,
 * an exchange-verified completed NIFTY history, original source-first-known
 * timestamps, a listed option, actual synchronized L1 quotes, depth and OI,
 * and independently stored Indian fee/capital assumptions. No old midnight
 * labels, invented expiry quotes, inferred rights or broker order route.
 *
 * <p>Actual evaluator execution is research only, NOT a backtest E2E pass:
 * after-cost fills, walk-forward/OOS, original market custody verification,
 * and an independent acceptance record are still separate.
 */
@Service
public class Fno181182VerifiedHistoricalReplayService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String INDEX = FnoVerifiedNiftyLongOptionSourceStore.NIFTY;
    private static final Duration MAX_SKEW = Duration.ofSeconds(90);
    private static final Duration MAX_AGE = Duration.ofSeconds(180);
    private final FnoVerifiedNiftyLongOptionSourceStore source;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean indexRights, optionRights, discoveryRights;

    @Autowired
    public Fno181182VerifiedHistoricalReplayService(
            FnoVerifiedNiftyLongOptionSourceStore source,
            MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.nifty-daily-close-research-rights-verified:false}")
            boolean indexRights,
            @Value("${minttick.fno-research.long-option-quote-research-rights-verified:false}")
            boolean optionRights,
            @Value("${minttick.fno-research.long-option-contract-discovery-verified:false}")
            boolean discoveryRights) {
        this(source, calendar, Clock.systemUTC(),
            indexRights, optionRights, discoveryRights);
    }

    Fno181182VerifiedHistoricalReplayService(
            FnoVerifiedNiftyLongOptionSourceStore source,
            MarketSessionCalendar calendar, Clock clock,
            boolean indexRights, boolean optionRights, boolean discoveryRights) {
        this.source = Objects.requireNonNull(source);
        this.calendar = Objects.requireNonNull(calendar);
        this.clock = Objects.requireNonNull(clock);
        this.indexRights = indexRights;
        this.optionRights = optionRights;
        this.discoveryRights = discoveryRights;
    }

    public record Report(
        String strategyId, String state, String reasonCode,
        int verifiedIndexSessions, int verifiedOptionQuotes,
        boolean originalRightsJoinedInput, boolean evaluatorInvoked,
        boolean independentHistoricalE2E, boolean paperLedgerE2E,
        boolean liveBrokerE2E, boolean brokerOrdersPlaced) {}

    /**
     * @param strategyId exactly STR-181 or STR-182
     * @param optionKey explicit V106 source-listed instrument, NEVER selected
     *                  heuristically from a currently available option chain
     * @param asOf exact historical decision time, not the time of this read
     */
    public Report inspect(String strategyId, String optionKey, Instant asOf) {
        if (!FnoLongCallV1.STRATEGY_ID.equals(strategyId)
                && !FnoLongPutV1.STRATEGY_ID.equals(strategyId))
            return blocked(strategyId, "HISTORICAL_OPTION_STRATEGY_UNSUPPORTED", 0);
        if (!indexRights || !optionRights || !discoveryRights)
            return blocked(strategyId, "ORIGINAL_INDEX_OPTION_SOURCE_RIGHTS_NOT_VERIFIED", 0);
        if (optionKey == null || !optionKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || asOf == null || asOf.isAfter(clock.instant())
                || asOf.isBefore(clock.instant().minus(Duration.ofDays(366)))
                || !calendar.isInsideCashSession(asOf))
            return blocked(strategyId, "HISTORICAL_NIFTY_OPTION_ASOF_SCOPE_INVALID", 0);

        final List<VerifiedClose> closes;
        try {
            closes = source.verifiedRecentCloses(asOf);
        } catch (RuntimeException unavailable) {
            return blocked(strategyId, "ORIGINAL_NIFTY_DAILY_SOURCE_UNAVAILABLE", 0);
        }
        if (!FnoOptionsResearchService.validHistory(closes, calendar, asOf))
            return blocked(strategyId, "ORIGINAL_35_COMPLETED_NIFTY_SESSIONS_MISSING",
                closes == null ? 0 : closes.size());

        boolean isCall = FnoLongCallV1.STRATEGY_ID.equals(strategyId);
        final OptionProof q;
        try {
            var found = source.oneCurrentOption(optionKey, isCall ? "CE" : "PE", asOf);
            if (found == null || found.isEmpty())
                return blocked(strategyId, "ORIGINAL_OPTION_QUOTE_DEPTH_OI_COSTS_MISSING",
                    closes.size());
            q = found.get();
        } catch (RuntimeException unavailable) {
            return blocked(strategyId, "ORIGINAL_OPTION_SOURCE_UNAVAILABLE", closes.size());
        }
        if (!validOption(q, optionKey, isCall ? "CE" : "PE",
                closes.getLast(), asOf))
            return blocked(strategyId, "OPTION_ORIGINAL_PROVIDER_CONTRACT_TIME_MISMATCH",
                closes.size());
        // This path calls the same real Java strategy evaluator used by the
        // current live research service; no alternative trading rules.
        String status, reason;
        if (isCall) {
            var bars = closes.stream().map(c -> new FnoLongCallV1.CompletedClose(
                c.session(), c.provider(), c.verifiedSessionCloseAt(),
                c.firstKnownAt(), c.close(), true)).toList();
            var contract = new FnoLongCallV1.Contract(
                q.optionKey(), INDEX, "CE", q.strike(), q.tickSize(),
                q.listedFrom(), q.expiry(), q.lotSize(), true);
            var underlying = new FnoLongCallV1.UnderlyingQuote(
                q.provider(), INDEX, q.indexPrice(),
                q.indexExchangeAt(), q.indexFirstObservedAt());
            var option = new FnoLongCallV1.CallQuote(
                q.provider(), q.optionKey(), q.optionBid(), q.optionAsk(),
                q.askDepthLots(), q.openInterestLots(),
                q.optionExchangeAt(), q.optionFirstObservedAt());
            var assumptions = new FnoLongCallV1.Assumptions(
                q.hypotheticalMoveAtExpiryBps(), q.buySlippageBps(),
                q.allInFeesPerLotInr(), q.minScenarioRewardRiskBps(),
                q.maxOptionSpreadBps(), q.maxStrikeDistanceBps(),
                q.minAskDepthLots(), q.minOpenInterestLots(),
                q.researchCapitalInr(), q.maxCapitalAllocationBps());
            var result = FnoLongCallV1.evaluate(contract, bars,
                underlying, option, new FnoLongCallV1.SourceRights(true,true,true),
                assumptions, asOf);
            status = result.status().name();
            reason = result.reasonCode();
        } else {
            var bars = closes.stream().map(c -> new FnoLongPutV1.CompletedClose(
                c.session(), c.provider(), c.verifiedSessionCloseAt(),
                c.firstKnownAt(), c.close(), true)).toList();
            var contract = new FnoLongPutV1.Contract(
                q.optionKey(), INDEX, "PE", q.strike(), q.tickSize(),
                q.listedFrom(), q.expiry(), q.lotSize(), true);
            var underlying = new FnoLongPutV1.UnderlyingQuote(
                q.provider(), INDEX, q.indexPrice(),
                q.indexExchangeAt(), q.indexFirstObservedAt());
            var option = new FnoLongPutV1.PutQuote(
                q.provider(), q.optionKey(), q.optionBid(), q.optionAsk(),
                q.askDepthLots(), q.openInterestLots(),
                q.optionExchangeAt(), q.optionFirstObservedAt());
            var assumptions = new FnoLongPutV1.Assumptions(
                q.hypotheticalMoveAtExpiryBps(), q.buySlippageBps(),
                q.allInFeesPerLotInr(), q.minScenarioRewardRiskBps(),
                q.maxOptionSpreadBps(), q.maxStrikeDistanceBps(),
                q.minAskDepthLots(), q.minOpenInterestLots(),
                q.researchCapitalInr(), q.maxCapitalAllocationBps());
            var result = FnoLongPutV1.evaluate(contract, bars,
                underlying, option, new FnoLongPutV1.SourceRights(true,true,true),
                assumptions, asOf);
            status = result.status().name();
            reason = result.reasonCode();
        }
        return new Report(strategyId,
            status.startsWith("BLOCKED")
                ? "ORIGINAL_SOURCE_EVALUATOR_BLOCKED"
                : "ORIGINAL_SOURCE_RESEARCH_EVALUATOR_RAN_NOT_E2E",
            reason, closes.size(), 1, true, true,
            false, false, false, false);
    }

    private static boolean validOption(
            OptionProof q, String key, String side, VerifiedClose last,
            Instant asOf) {
        if (q == null || !key.equals(q.optionKey()) || !side.equals(q.optionType())
                || !INDEX.equals(q.indexKey()) || !last.provider().equals(q.provider())
                || !INDEX.equals(last.indexKey()) || last.firstKnownAt() == null
                || q.optionExchangeAt() == null || q.indexExchangeAt() == null
                || q.optionFirstObservedAt() == null || q.indexFirstObservedAt() == null
                || q.optionExchangeAt().isAfter(q.optionFirstObservedAt())
                || q.indexExchangeAt().isAfter(q.indexFirstObservedAt())
                || q.optionFirstObservedAt().isAfter(asOf)
                || q.indexFirstObservedAt().isAfter(asOf)
                || q.optionExchangeAt().isAfter(asOf)
                || q.indexExchangeAt().isAfter(asOf)
                || last.firstKnownAt().isAfter(q.optionExchangeAt())
                || last.firstKnownAt().isAfter(q.indexExchangeAt())
                || Duration.between(q.optionExchangeAt(), q.indexExchangeAt()).abs()
                    .compareTo(MAX_SKEW) > 0
                || Duration.between(q.optionExchangeAt(), asOf).compareTo(MAX_AGE) > 0
                || Duration.between(q.indexExchangeAt(), asOf).compareTo(MAX_AGE) > 0
                || q.listedFrom() == null || q.expiry() == null
                || q.listedFrom().isAfter(asOf.atZone(IST).toLocalDate())
                || q.expiry().isBefore(asOf.atZone(IST).toLocalDate().plusDays(3))
                || q.expiry().isAfter(asOf.atZone(IST).toLocalDate().plusDays(45))
                || q.lotSize() <= 0 || q.strike() == null || q.tickSize() == null
                || q.strike().signum() <= 0 || q.tickSize().signum() <= 0
                || q.strike().remainder(q.tickSize()).signum() != 0
                || q.optionBid() == null || q.optionAsk() == null
                || q.optionBid().signum() <= 0
                || q.optionAsk().compareTo(q.optionBid()) < 0
                || q.indexPrice() == null || q.indexPrice().signum() <= 0
                || q.askDepthLots() <= 0 || q.openInterestLots() <= 0
                || q.researchCapitalInr() == null
                || q.researchCapitalInr().signum() <= 0
                || q.allInFeesPerLotInr() == null
                || q.allInFeesPerLotInr().signum() <= 0
                || q.contractMasterSha256() == null
                || !q.contractMasterSha256().matches("[a-f0-9]{64}"))
            return false;
        return true;
    }

    private static Report blocked(String id, String reason, int rows) {
        return new Report(id, "BLOCKED_NOT_HISTORICAL_E2E", reason,
            rows, 0, false, false, false, false, false, false);
    }
}
