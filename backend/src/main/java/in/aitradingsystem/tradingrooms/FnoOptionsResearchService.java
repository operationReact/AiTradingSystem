package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore.OptionProof;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Distinct STR-181 CE and STR-182 PE research evaluators.
 * Signal-only EMA routes are insufficient: options need genuinely source-listed
 * contract identity, timed premium/depth/OI and reviewed cost/capital receipts.
 * No broker calls, orders, synthetic fallback, permissions or fund mutation.
 */
@Service
public final class FnoOptionsResearchService {
    private static final String INDEX = FnoLongOptionCrossoverV1.UNDERLYING;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final FnoVerifiedNiftyLongOptionSourceStore store;
    private final MarketSessionCalendar calendar;
    private final boolean indexResearchRights;
    private final boolean optionResearchRights;
    private final boolean discoveryRights;
    private final String ceKey;
    private final String peKey;

    @Autowired
    public FnoOptionsResearchService(FnoVerifiedNiftyLongOptionSourceStore store,
            MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.nifty-daily-close-research-rights-verified:false}")
                boolean indexResearchRights,
            @Value("${minttick.fno-research.long-option-quote-research-rights-verified:false}")
                boolean optionResearchRights,
            @Value("${minttick.fno-research.long-option-contract-discovery-verified:false}")
                boolean discoveryRights,
            @Value("${minttick.fno-research.long-option-ce-instrument-key:}") String ceKey,
            @Value("${minttick.fno-research.long-option-pe-instrument-key:}") String peKey) {
        this.store = store;
        this.calendar = calendar;
        this.indexResearchRights = indexResearchRights;
        this.optionResearchRights = optionResearchRights;
        this.discoveryRights = discoveryRights;
        this.ceKey = ceKey == null ? "" : ceKey.trim();
        this.peKey = peKey == null ? "" : peKey.trim();
    }

    public List<FnoFuturesResearchService.StrategyStatus> evaluateAll(Instant now) {
        if (now == null) throw new IllegalArgumentException("NIFTY_OPTION_ASOF_MISSING");
        if (!indexResearchRights || !optionResearchRights || !discoveryRights) {
            return List.of(blocked(true, now, "LONG_CALL_SOURCE_RIGHTS_UNVERIFIED", true, 0),
                blocked(false, now, "LONG_PUT_SOURCE_RIGHTS_UNVERIFIED", true, 0));
        }
        if (store == null || calendar == null) return bothBlocked(now,
            "LONG_OPTION_SOURCE_UNAVAILABLE", 0);
        if (!calendar.isInsideCashSession(now)) return bothBlocked(now,
            "LONG_OPTION_EXCHANGE_SESSION_CLOSED", 0);
        if (!validKey(ceKey) && !validKey(peKey)) return bothBlocked(now,
            "LONG_OPTION_LISTED_CONTRACT_NOT_CONFIGURED", 0);

        final List<VerifiedClose> closes;
        try {
            closes = store.verifiedRecentCloses(now);
        } catch (RuntimeException ignored) {
            return bothBlocked(now, "LONG_OPTION_DAILY_SOURCE_READ_UNAVAILABLE", 0);
        }
        if (!validHistory(closes, calendar, now)) return bothBlocked(now,
            "LONG_OPTION_35_COMPLETED_SOURCE_CLOSES_MISSING",
            closes == null ? 0 : closes.size());
        return List.of(one(true, now, closes), one(false, now, closes));
    }

    private List<FnoFuturesResearchService.StrategyStatus> bothBlocked(
            Instant now, String reason, int bars) {
        return List.of(blocked(true, now, reason, false, bars),
            blocked(false, now, reason, false, bars));
    }

    private FnoFuturesResearchService.StrategyStatus one(
            boolean call, Instant now, List<VerifiedClose> bars) {
        String key = call ? ceKey : peKey;
        String side = call ? "CE" : "PE";
        if (!validKey(key)) return blocked(call, now,
            "LONG_OPTION_LISTED_CONTRACT_NOT_CONFIGURED", false, bars.size());
        final Optional<OptionProof> proof;
        try {
            proof = store.oneCurrentOption(key, side, now);
        } catch (RuntimeException ignored) {
            return blocked(call, now, "LONG_OPTION_SOURCE_READ_UNAVAILABLE",
                false, bars.size());
        }
        if (proof == null || proof.isEmpty()) return blocked(call, now,
            "LONG_OPTION_LISTED_QUOTE_DEPTH_OI_COST_PROOF_MISSING", false, bars.size());
        var q = proof.get();
        if (!validOption(q, bars.getLast(), key, side, now)) return blocked(call, now,
            "LONG_OPTION_SOURCE_CONTRACT_PROVIDER_OR_TIMING_MISMATCH",
            false, bars.size());

        if (call) {
            var completed = new ArrayList<FnoLongCallV1.CompletedClose>();
            for (var b : bars) completed.add(new FnoLongCallV1.CompletedClose(
                b.session(), b.provider(), b.verifiedSessionCloseAt(), b.firstKnownAt(),
                b.close(), true));
            var contract = new FnoLongCallV1.Contract(q.optionKey(), INDEX, side,
                q.strike(), q.tickSize(), q.listedFrom(), q.expiry(), q.lotSize(), true);
            var underlying = new FnoLongCallV1.UnderlyingQuote(q.provider(), INDEX,
                q.indexPrice(), q.indexExchangeAt(), q.indexFirstObservedAt());
            var option = new FnoLongCallV1.CallQuote(q.provider(), q.optionKey(),
                q.optionBid(), q.optionAsk(), q.askDepthLots(), q.openInterestLots(),
                q.optionExchangeAt(), q.optionFirstObservedAt());
            var assumptions = new FnoLongCallV1.Assumptions(
                q.hypotheticalMoveAtExpiryBps(), q.buySlippageBps(),
                q.allInFeesPerLotInr(), q.minScenarioRewardRiskBps(),
                q.maxOptionSpreadBps(), q.maxStrikeDistanceBps(),
                q.minAskDepthLots(), q.minOpenInterestLots(),
                q.researchCapitalInr(), q.maxCapitalAllocationBps());
            var result = FnoLongCallV1.evaluate(contract, List.copyOf(completed),
                underlying, option, new FnoLongCallV1.SourceRights(true,true,true),
                assumptions, now);
            return status(result.strategyId(), result.status().name(),
                result.reasonCode(), now, q.provider(), 1, 2, bars.size(),
                "VERIFIED_NIFTY_CE_SOURCE_RESEARCH_ONLY");
        }

        var completed = new ArrayList<FnoLongPutV1.CompletedClose>();
        for (var b : bars) completed.add(new FnoLongPutV1.CompletedClose(
            b.session(), b.provider(), b.verifiedSessionCloseAt(), b.firstKnownAt(),
            b.close(), true));
        var contract = new FnoLongPutV1.Contract(q.optionKey(), INDEX, side,
            q.strike(), q.tickSize(), q.listedFrom(), q.expiry(), q.lotSize(), true);
        var underlying = new FnoLongPutV1.UnderlyingQuote(q.provider(), INDEX,
            q.indexPrice(), q.indexExchangeAt(), q.indexFirstObservedAt());
        var option = new FnoLongPutV1.PutQuote(q.provider(), q.optionKey(),
            q.optionBid(), q.optionAsk(), q.askDepthLots(), q.openInterestLots(),
            q.optionExchangeAt(), q.optionFirstObservedAt());
        var assumptions = new FnoLongPutV1.Assumptions(
            q.hypotheticalMoveAtExpiryBps(), q.buySlippageBps(),
            q.allInFeesPerLotInr(), q.minScenarioRewardRiskBps(),
            q.maxOptionSpreadBps(), q.maxStrikeDistanceBps(),
            q.minAskDepthLots(), q.minOpenInterestLots(),
            q.researchCapitalInr(), q.maxCapitalAllocationBps());
        var result = FnoLongPutV1.evaluate(contract, List.copyOf(completed),
            underlying, option, new FnoLongPutV1.SourceRights(true,true,true),
            assumptions, now);
        return status(result.strategyId(), result.status().name(),
            result.reasonCode(), now, q.provider(), 1, 2, bars.size(),
            "VERIFIED_NIFTY_PE_SOURCE_RESEARCH_ONLY");
    }

    static boolean validHistory(List<VerifiedClose> rows,
            MarketSessionCalendar calendar, Instant now) {
        if (rows == null || rows.size() < 35 || rows.size() > 90) return false;
        LocalDate previous = null;
        String provider = null;
        for (var c : rows) {
            if (c == null || !INDEX.equals(c.indexKey())
                    || !supported(c.provider()) || c.session() == null
                    || c.close() == null || c.close().signum() <= 0
                    || c.firstKnownAt() == null || c.verifiedSessionCloseAt() == null
                    || !c.verifiedSessionCloseAt().equals(c.session().atTime(15,30)
                        .atZone(IST).toInstant())
                    || c.firstKnownAt().isBefore(c.verifiedSessionCloseAt())
                    || c.firstKnownAt().isAfter(now)
                    || c.firstKnownAt().isAfter(
                        c.verifiedSessionCloseAt().plus(Duration.ofHours(36)))
                    || !calendar.isTradingDay(c.session())
                    || (provider != null && !provider.equals(c.provider()))
                    || (previous != null && !previous.isBefore(c.session()))) return false;
            if (previous != null) {
                for (LocalDate d = previous.plusDays(1); d.isBefore(c.session());
                        d = d.plusDays(1)) {
                    if (calendar.isTradingDay(d)) return false;
                }
            }
            previous = c.session();
            provider = c.provider();
        }
        return previous != null
            && !previous.isAfter(now.atZone(IST).toLocalDate())
            && previous.isAfter(now.atZone(IST).toLocalDate().minusDays(5));
    }

    private static boolean validOption(OptionProof q, VerifiedClose last, String key,
                                       String side, Instant now) {
        if (q == null || !supported(q.provider()) || !q.provider().equals(last.provider())
                || !INDEX.equals(q.indexKey()) || !key.equals(q.optionKey())
                || !side.equals(q.optionType())
                || q.expiry() == null || q.listedFrom() == null
                || q.strike() == null || q.tickSize() == null
                || q.strike().signum() <= 0 || q.tickSize().signum() <= 0
                || q.strike().remainder(q.tickSize()).signum() != 0
                || q.lotSize() <= 0 || q.lotSize() > 100000
                || q.indexExchangeAt() == null || q.indexFirstObservedAt() == null
                || q.optionExchangeAt() == null || q.optionFirstObservedAt() == null
                || q.indexPrice() == null || q.indexPrice().signum() <= 0
                || q.optionBid() == null || q.optionAsk() == null
                || q.optionBid().signum() <= 0 || q.optionAsk().signum() <= 0
                || q.optionAsk().compareTo(q.optionBid()) < 0
                || q.indexFirstObservedAt().isAfter(now)
                || q.optionFirstObservedAt().isAfter(now)
                || q.indexExchangeAt().isAfter(q.indexFirstObservedAt())
                || q.optionExchangeAt().isAfter(q.optionFirstObservedAt())
                || Duration.between(q.indexExchangeAt(), q.optionExchangeAt())
                    .abs().compareTo(Duration.ofSeconds(90)) > 0
                || last.firstKnownAt().isAfter(q.optionExchangeAt())
                || last.firstKnownAt().isAfter(q.indexExchangeAt())
                || q.listedFrom().isAfter(now.atZone(IST).toLocalDate())
                || q.expiry().isBefore(now.atZone(IST).toLocalDate().plusDays(3))
                || q.expiry().isAfter(now.atZone(IST).toLocalDate().plusDays(45))
                || q.askDepthLots() < 1 || q.openInterestLots() < 1
                || q.researchCapitalInr() == null || q.researchCapitalInr().signum() <= 0
                || q.allInFeesPerLotInr() == null
                || q.allInFeesPerLotInr().signum() <= 0) return false;
        return true;
    }

    private static boolean validKey(String key) {
        return key != null && key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,80}");
    }
    private static boolean supported(String source) {
        return "UPSTOX_ANALYTICS".equals(source) || "ANGEL_ONE_SMARTAPI".equals(source);
    }

    private static FnoFuturesResearchService.StrategyStatus blocked(
            boolean call, Instant now, String reason, boolean denied, int bars) {
        String status = call
            ? FnoLongCallV1.evaluate(null, List.of(), null, null,
                new FnoLongCallV1.SourceRights(!denied,!denied,!denied),
                null, now).status().name()
            : FnoLongPutV1.evaluate(null, List.of(), null, null,
                new FnoLongPutV1.SourceRights(!denied,!denied,!denied),
                null, now).status().name();
        return status(call ? FnoLongCallV1.STRATEGY_ID : FnoLongPutV1.STRATEGY_ID,
            status, reason, now, "NONE", 0, 0, bars, "NIFTY_OPTION_SOURCE_BLOCKED");
    }
    private static FnoFuturesResearchService.StrategyStatus status(
            String id, String result, String reason, Instant now,
            String provider, int contracts, int quotes, int history, String input) {
        return new FnoFuturesResearchService.StrategyStatus(
            id, result, reason, now, provider, contracts, quotes, history, input);
    }
}
