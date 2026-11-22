package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyDebitSpreadSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyDebitSpreadSourceStore.Proof;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyDebitSpreadSourceStore.Leg;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * STR-183 bull call debit and STR-184 bear put debit spread:
 * distinct full two-leg, source-rights verified, after-cost research only.
 * A single option quote or directional EMA signal cannot certify a spread.
 * No broker-side execution, positions, buying power mutation or source seeding.
 */
@Service
public final class FnoDebitSpreadResearchService {
    private static final String NIFTY = FnoLongOptionCrossoverV1.UNDERLYING;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final FnoVerifiedNiftyLongOptionSourceStore dailyStore;
    private final FnoVerifiedNiftyDebitSpreadSourceStore spreadStore;
    private final MarketSessionCalendar calendar;
    private final boolean dailyRights;
    private final boolean optionRights;
    private final boolean contractRights;
    private final boolean spreadRights;
    private final String bullLower;
    private final String bullUpper;
    private final String bearLower;
    private final String bearUpper;

    @Autowired
    public FnoDebitSpreadResearchService(FnoVerifiedNiftyLongOptionSourceStore dailyStore,
            FnoVerifiedNiftyDebitSpreadSourceStore spreadStore,
            MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.nifty-daily-close-research-rights-verified:false}")
                boolean dailyRights,
            @Value("${minttick.fno-research.long-option-quote-research-rights-verified:false}")
                boolean optionRights,
            @Value("${minttick.fno-research.long-option-contract-discovery-verified:false}")
                boolean contractRights,
            @Value("${minttick.fno-research.debit-spread-research-rights-verified:false}")
                boolean spreadRights,
            @Value("${minttick.fno-research.debit-bull-lower-ce-key:}") String bullLower,
            @Value("${minttick.fno-research.debit-bull-upper-ce-key:}") String bullUpper,
            @Value("${minttick.fno-research.debit-bear-lower-pe-key:}") String bearLower,
            @Value("${minttick.fno-research.debit-bear-upper-pe-key:}") String bearUpper) {
        this.dailyStore = dailyStore;
        this.spreadStore = spreadStore;
        this.calendar = calendar;
        this.dailyRights = dailyRights;
        this.optionRights = optionRights;
        this.contractRights = contractRights;
        this.spreadRights = spreadRights;
        this.bullLower = bullLower == null ? "" : bullLower.trim();
        this.bullUpper = bullUpper == null ? "" : bullUpper.trim();
        this.bearLower = bearLower == null ? "" : bearLower.trim();
        this.bearUpper = bearUpper == null ? "" : bearUpper.trim();
    }

    public List<FnoFuturesResearchService.StrategyStatus> evaluateAll(Instant now) {
        if (now == null) throw new IllegalArgumentException("DEBIT_SPREAD_ASOF_MISSING");
        if (!dailyRights || !optionRights || !contractRights || !spreadRights) {
            return List.of(blocked(true, now, "DEBIT_SPREAD_SOURCE_RIGHTS_UNVERIFIED", true, 0),
                blocked(false, now, "DEBIT_SPREAD_SOURCE_RIGHTS_UNVERIFIED", true, 0));
        }
        if (dailyStore == null || spreadStore == null || calendar == null)
            return bothBlocked(now, "DEBIT_SPREAD_TYPED_SOURCE_MISSING", 0);
        if (!calendar.isInsideCashSession(now))
            return bothBlocked(now, "DEBIT_SPREAD_CASH_EXCHANGE_SESSION_CLOSED", 0);
        if (!validKeys(bullLower, bullUpper) && !validKeys(bearLower, bearUpper))
            return bothBlocked(now, "DEBIT_SPREAD_REAL_TWO_LEG_KEYS_NOT_CONFIGURED", 0);
        final List<VerifiedClose> closes;
        try {
            closes = dailyStore.verifiedRecentCloses(now);
        } catch (RuntimeException unavailable) {
            return bothBlocked(now, "DEBIT_SPREAD_DAILY_SOURCE_UNAVAILABLE", 0);
        }
        if (!FnoOptionsResearchService.validHistory(closes, calendar, now))
            return bothBlocked(now, "DEBIT_SPREAD_35_COMPLETED_CLOSES_INVALID",
                closes == null ? 0 : closes.size());
        return List.of(one(true, now, closes), one(false, now, closes));
    }


    /**
     * Historical original-source evaluation of exactly one two-leg debit
     * spread; never query the sibling CE/PE spread or use synthetic quotes.
     * Existing V107 SQL verifies original source receipt rights, captured
     * as-of timestamps, both option legs, depth, OI and source cost inputs.
     */
    public FnoFuturesResearchService.StrategyStatus evaluateOne(
            String strategyId, Instant asOf) {
        if (asOf == null) throw new IllegalArgumentException("DEBIT_SPREAD_ASOF_MISSING");
        boolean bullish;
        if (FnoBullCallDebitSpreadV1.STRATEGY_ID.equals(strategyId)) bullish=true;
        else if (FnoBearPutDebitSpreadV1.STRATEGY_ID.equals(strategyId)) bullish=false;
        else throw new IllegalArgumentException("HISTORICAL_DEBIT_STRATEGY_UNSUPPORTED");
        if (!dailyRights || !optionRights || !contractRights || !spreadRights)
            return blocked(bullish,asOf,"DEBIT_SPREAD_SOURCE_RIGHTS_UNVERIFIED",true,0);
        if (dailyStore==null || spreadStore==null || calendar==null)
            return blocked(bullish,asOf,"DEBIT_SPREAD_TYPED_SOURCE_MISSING",false,0);
        if (!calendar.isInsideCashSession(asOf))
            return blocked(bullish,asOf,"DEBIT_SPREAD_CASH_EXCHANGE_SESSION_CLOSED",false,0);
        String lower=bullish?bullLower:bearLower, upper=bullish?bullUpper:bearUpper;
        if (!validKeys(lower,upper))
            return blocked(bullish,asOf,"DEBIT_SPREAD_REAL_TWO_LEG_KEYS_NOT_CONFIGURED",false,0);
        final List<VerifiedClose> closes;
        try {
            closes=dailyStore.verifiedRecentCloses(asOf);
        } catch (RuntimeException unavailable) {
            return blocked(bullish,asOf,"DEBIT_SPREAD_DAILY_SOURCE_UNAVAILABLE",false,0);
        }
        if (!FnoOptionsResearchService.validHistory(closes,calendar,asOf))
            return blocked(bullish,asOf,"DEBIT_SPREAD_35_COMPLETED_CLOSES_INVALID",
                false,closes==null?0:closes.size());
        return one(bullish,asOf,closes);
    }

    private List<FnoFuturesResearchService.StrategyStatus> bothBlocked(
            Instant now, String reason, int sessions) {
        return List.of(blocked(true, now, reason, false, sessions),
            blocked(false, now, reason, false, sessions));
    }

    private FnoFuturesResearchService.StrategyStatus one(boolean bullish,
            Instant now, List<VerifiedClose> closes) {
        String lower = bullish ? bullLower : bearLower;
        String upper = bullish ? bullUpper : bearUpper;
        String kind = bullish ? "BULL_CALL" : "BEAR_PUT";
        if (!validKeys(lower, upper)) return blocked(bullish, now,
            "DEBIT_SPREAD_REAL_TWO_LEG_KEYS_NOT_CONFIGURED", false, closes.size());
        final Optional<Proof> proof;
        try {
            proof = spreadStore.oneCurrentSpread(kind, lower, upper, now);
        } catch (RuntimeException unavailable) {
            return blocked(bullish, now,
                "DEBIT_SPREAD_SOURCE_READ_UNAVAILABLE", false, closes.size());
        }
        if (proof == null || proof.isEmpty()) return blocked(bullish, now,
            "DEBIT_SPREAD_TWO_LEG_QUOTES_COSTS_OR_DEPTH_PROOF_MISSING",
            false, closes.size());
        var p = proof.get();
        if (!valid(p, closes.getLast(), kind, lower, upper, now))
            return blocked(bullish, now,
                "DEBIT_SPREAD_PROVIDER_CONTRACT_OR_FIRST_KNOWN_MISMATCH",
                false, closes.size());
        if (bullish) {
            var bars = new ArrayList<FnoBullCallDebitSpreadV1.CompletedClose>();
            for (var c : closes) bars.add(new FnoBullCallDebitSpreadV1.CompletedClose(
                c.session(), c.provider(), c.verifiedSessionCloseAt(),
                c.firstKnownAt(), c.close(), true));
            var lowerContract = new FnoBullCallDebitSpreadV1.Contract(
                p.lower().key(), NIFTY, "CE", p.lower().strike(),
                p.lower().tickSize(), p.lower().listedFrom(), p.lower().expiry(),
                p.lower().lotSize(), true);
            var upperContract = new FnoBullCallDebitSpreadV1.Contract(
                p.higher().key(), NIFTY, "CE", p.higher().strike(),
                p.higher().tickSize(), p.higher().listedFrom(), p.higher().expiry(),
                p.higher().lotSize(), true);
            var underlying = new FnoBullCallDebitSpreadV1.UnderlyingQuote(
                p.provider(), NIFTY, p.index().price(),
                p.index().exchangeAt(), p.index().firstKnownAt());
            var longQuote = bullQuote(p.lower(), p.provider());
            var shortQuote = bullQuote(p.higher(), p.provider());
            var r = p.risk();
            var assumptions = new FnoBullCallDebitSpreadV1.Assumptions(
                r.buySlippageBps(),r.sellSlippageBps(),r.feesInr(),
                r.maxEachLegSpreadBps(),r.minDepthLots(),r.minOiLots(),
                r.minRewardRiskBps(),r.capitalInr(),r.maxAllocationBps(),
                r.maxStrikeDistanceBps());
            var result = FnoBullCallDebitSpreadV1.evaluate(
                lowerContract, upperContract, List.copyOf(bars),
                underlying, longQuote, shortQuote,
                new FnoBullCallDebitSpreadV1.SourceRights(true,true,true),
                assumptions, now);
            return status(result.strategyId(), result.status().name(),
                result.reasonCode(), now, p.provider(), bars.size(),
                "VERIFIED_TWO_LEG_CE_DEBIT_SPREAD_RESEARCH_NO_ORDER");
        }

        var bars = new ArrayList<FnoBearPutDebitSpreadV1.CompletedClose>();
        for (var c : closes) bars.add(new FnoBearPutDebitSpreadV1.CompletedClose(
            c.session(), c.provider(), c.verifiedSessionCloseAt(),
            c.firstKnownAt(), c.close(), true));
        var lowerContract = new FnoBearPutDebitSpreadV1.Contract(
            p.lower().key(), NIFTY, "PE", p.lower().strike(),
            p.lower().tickSize(), p.lower().listedFrom(), p.lower().expiry(),
            p.lower().lotSize(), true);
        var upperContract = new FnoBearPutDebitSpreadV1.Contract(
            p.higher().key(), NIFTY, "PE", p.higher().strike(),
            p.higher().tickSize(), p.higher().listedFrom(), p.higher().expiry(),
            p.higher().lotSize(), true);
        var underlying = new FnoBearPutDebitSpreadV1.UnderlyingQuote(
            p.provider(), NIFTY, p.index().price(), p.index().exchangeAt(),
            p.index().firstKnownAt());
        var longQuote = bearQuote(p.higher(), p.provider());
        var shortQuote = bearQuote(p.lower(), p.provider());
        var r = p.risk();
        var assumptions = new FnoBearPutDebitSpreadV1.Assumptions(
            r.buySlippageBps(),r.sellSlippageBps(),r.feesInr(),
            r.maxEachLegSpreadBps(),r.minDepthLots(),r.minOiLots(),
            r.minRewardRiskBps(),r.capitalInr(),r.maxAllocationBps(),
            r.maxStrikeDistanceBps());
        var result = FnoBearPutDebitSpreadV1.evaluate(
            lowerContract, upperContract, List.copyOf(bars),
            underlying, longQuote, shortQuote,
            new FnoBearPutDebitSpreadV1.SourceRights(true,true,true),
            assumptions, now);
        return status(result.strategyId(), result.status().name(),
            result.reasonCode(), now, p.provider(), bars.size(),
            "VERIFIED_TWO_LEG_PE_DEBIT_SPREAD_RESEARCH_NO_ORDER");
    }

    private static FnoBullCallDebitSpreadV1.OptionQuote bullQuote(Leg q, String provider) {
        return new FnoBullCallDebitSpreadV1.OptionQuote(provider, q.key(),
            q.bid(), q.ask(), q.bidDepthLots(), q.askDepthLots(),
            q.openInterestLots(), q.exchangeAt(), q.firstKnownAt());
    }
    private static FnoBearPutDebitSpreadV1.OptionQuote bearQuote(Leg q, String provider) {
        return new FnoBearPutDebitSpreadV1.OptionQuote(provider, q.key(),
            q.bid(), q.ask(), q.bidDepthLots(), q.askDepthLots(),
            q.openInterestLots(), q.exchangeAt(), q.firstKnownAt());
    }

    private static boolean valid(Proof p, VerifiedClose last,
            String side, String lowerKey, String upperKey, Instant now) {
        if (p == null || last == null || !NIFTY.equals(p.underlyingKey())
                || !p.provider().equals(last.provider()) || !side.equals(p.spreadType())
                || p.lower() == null || p.higher() == null || p.index() == null
                || p.risk() == null || !lowerKey.equals(p.lower().key())
                || !upperKey.equals(p.higher().key())
                || !p.lower().kind().equals(side.equals("BULL_CALL") ? "CE" : "PE")
                || !p.lower().kind().equals(p.higher().kind())
                || p.lower().strike() == null || p.higher().strike() == null
                || p.lower().strike().compareTo(p.higher().strike()) >= 0
                || p.lower().expiry() == null
                || !p.lower().expiry().equals(p.higher().expiry())
                || p.lower().lotSize() <= 0
                || p.lower().lotSize() != p.higher().lotSize()
                || p.contractMasterDigest() == null
                || !p.contractMasterDigest().matches("[a-f0-9]{64}")
                || p.index().price() == null || p.index().price().signum() <= 0
                || p.index().exchangeAt() == null || p.index().firstKnownAt() == null
                || p.index().exchangeAt().isAfter(p.index().firstKnownAt())
                || p.index().firstKnownAt().isAfter(now)
                || p.risk().feesInr() == null || p.risk().feesInr().signum() <= 0
                || p.risk().capitalInr() == null || p.risk().capitalInr().signum() <= 0)
            return false;
        Instant earliest = p.index().exchangeAt();
        Instant latest = earliest;
        for (var leg : List.of(p.lower(),p.higher())) {
            if (leg.key() == null || leg.listedFrom() == null
                    || leg.strike() == null || leg.tickSize() == null
                    || leg.strike().signum() <= 0 || leg.tickSize().signum() <= 0
                    || leg.strike().remainder(leg.tickSize()).signum() != 0
                    || leg.exchangeAt() == null || leg.firstKnownAt() == null
                    || leg.exchangeAt().isAfter(leg.firstKnownAt())
                    || leg.firstKnownAt().isAfter(now)
                    || leg.bid() == null || leg.ask() == null
                    || leg.bid().signum() <= 0 || leg.ask().compareTo(leg.bid()) < 0
                    || leg.bidDepthLots() <= 0 || leg.askDepthLots() <= 0
                    || leg.openInterestLots() <= 0
                    || last.firstKnownAt().isAfter(leg.exchangeAt())) return false;
            if (leg.exchangeAt().isBefore(earliest)) earliest = leg.exchangeAt();
            if (leg.exchangeAt().isAfter(latest)) latest = leg.exchangeAt();
        }
        if (last.firstKnownAt().isAfter(p.index().exchangeAt())) return false;
        return Duration.between(earliest, latest).compareTo(Duration.ofSeconds(90)) <= 0
            && Duration.between(latest, now).compareTo(Duration.ofSeconds(180)) <= 0
            && p.lower().expiry().isAfter(now.atZone(IST).toLocalDate().plusDays(2))
            && !p.lower().expiry().isAfter(now.atZone(IST).toLocalDate().plusDays(45))
            && !p.lower().listedFrom().isAfter(now.atZone(IST).toLocalDate())
            && !p.higher().listedFrom().isAfter(now.atZone(IST).toLocalDate());
    }

    private static boolean validKeys(String lower, String higher) {
        return lower != null && higher != null
            && lower.matches("NSE_FO\\|[A-Za-z0-9._-]{1,80}")
            && higher.matches("NSE_FO\\|[A-Za-z0-9._-]{1,80}")
            && !lower.equals(higher);
    }

    private static FnoFuturesResearchService.StrategyStatus blocked(
            boolean bull, Instant now, String reason, boolean denied, int sessions) {
        String type = bull
            ? FnoBullCallDebitSpreadV1.evaluate(null,null,List.of(),null,null,null,
                new FnoBullCallDebitSpreadV1.SourceRights(!denied,!denied,!denied),
                null,now).status().name()
            : FnoBearPutDebitSpreadV1.evaluate(null,null,List.of(),null,null,null,
                new FnoBearPutDebitSpreadV1.SourceRights(!denied,!denied,!denied),
                null,now).status().name();
        return new FnoFuturesResearchService.StrategyStatus(
            bull ? FnoBullCallDebitSpreadV1.STRATEGY_ID
                 : FnoBearPutDebitSpreadV1.STRATEGY_ID,
            type, reason, now, "NONE", 0, 0, sessions, "DEBIT_SPREAD_SOURCE_BLOCKED");
    }

    private static FnoFuturesResearchService.StrategyStatus status(
            String id, String result, String reason, Instant now, String provider,
            int sessions, String inputState) {
        return new FnoFuturesResearchService.StrategyStatus(id, result, reason,
            now, provider, 2, 3, sessions, inputState);
    }
}
