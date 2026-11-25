package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyCreditSpreadSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyCreditSpreadSourceStore.Proof;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyCreditSpreadSourceStore.Leg;
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
 * STR-185 bull put credit and STR-186 bear call credit spreads:
 * distinct full two-leg, source-rights verified, after-cost research only.
 * A single option quote or directional EMA signal cannot certify a spread.
 * No broker-side execution, positions, buying power mutation or source seeding.
 */
@Service
public final class FnoCreditSpreadResearchService {
    private static final String NIFTY = FnoLongOptionCrossoverV1.UNDERLYING;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final FnoVerifiedNiftyLongOptionSourceStore dailyStore;
    private final FnoVerifiedNiftyCreditSpreadSourceStore spreadStore;
    private final MarketSessionCalendar calendar;
    private final boolean dailyRights;
    private final boolean optionRights;
    private final boolean contractRights;
    private final boolean spreadRights;
    private final String creditAccountScope;
    private final String bullLower;
    private final String bullUpper;
    private final String bearLower;
    private final String bearUpper;

    @Autowired
    public FnoCreditSpreadResearchService(FnoVerifiedNiftyLongOptionSourceStore dailyStore,
            FnoVerifiedNiftyCreditSpreadSourceStore spreadStore,
            MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.nifty-daily-close-research-rights-verified:false}")
                boolean dailyRights,
            @Value("${minttick.fno-research.long-option-quote-research-rights-verified:false}")
                boolean optionRights,
            @Value("${minttick.fno-research.long-option-contract-discovery-verified:false}")
                boolean contractRights,
            @Value("${minttick.fno-research.credit-spread-research-rights-verified:false}")
                boolean spreadRights,
            @Value("${minttick.fno-research.credit-spread-account-scope:}")
                String creditAccountScope,
            @Value("${minttick.fno-research.credit-bull-lower-pe-key:}") String bullLower,
            @Value("${minttick.fno-research.credit-bull-upper-pe-key:}") String bullUpper,
            @Value("${minttick.fno-research.credit-bear-lower-ce-key:}") String bearLower,
            @Value("${minttick.fno-research.credit-bear-upper-ce-key:}") String bearUpper) {
        this.dailyStore = dailyStore;
        this.spreadStore = spreadStore;
        this.calendar = calendar;
        this.dailyRights = dailyRights;
        this.optionRights = optionRights;
        this.contractRights = contractRights;
        this.spreadRights = spreadRights;
        this.creditAccountScope = creditAccountScope == null ? "" : creditAccountScope.trim();
        this.bullLower = bullLower == null ? "" : bullLower.trim();
        this.bullUpper = bullUpper == null ? "" : bullUpper.trim();
        this.bearLower = bearLower == null ? "" : bearLower.trim();
        this.bearUpper = bearUpper == null ? "" : bearUpper.trim();
    }

    public List<FnoFuturesResearchService.StrategyStatus> evaluateAll(Instant now) {
        if (now == null) throw new IllegalArgumentException("CREDIT_SPREAD_ASOF_MISSING");
        if (!dailyRights || !optionRights || !contractRights || !spreadRights) {
            return List.of(blocked(true, now, "CREDIT_SPREAD_SOURCE_RIGHTS_UNVERIFIED", true, 0),
                blocked(false, now, "CREDIT_SPREAD_SOURCE_RIGHTS_UNVERIFIED", true, 0));
        }
        if (dailyStore == null || spreadStore == null || calendar == null)
            return bothBlocked(now, "CREDIT_SPREAD_TYPED_SOURCE_MISSING", 0);
        if (!calendar.isInsideCashSession(now))
            return bothBlocked(now, "CREDIT_SPREAD_CASH_EXCHANGE_SESSION_CLOSED", 0);
        if (!creditAccountScope.matches("[A-Za-z0-9_-]{8,80}"))
            return bothBlocked(now, "CREDIT_SPREAD_ACCOUNT_OR_MARGIN_NOT_CONFIGURED", 0);
        if (!validKeys(bullLower, bullUpper) && !validKeys(bearLower, bearUpper))
            return bothBlocked(now, "CREDIT_SPREAD_REAL_TWO_LEG_KEYS_NOT_CONFIGURED", 0);
        final List<VerifiedClose> closes;
        try {
            closes = dailyStore.verifiedRecentCloses(now);
        } catch (RuntimeException unavailable) {
            return bothBlocked(now, "CREDIT_SPREAD_DAILY_SOURCE_UNAVAILABLE", 0);
        }
        if (!FnoOptionsResearchService.validHistory(closes, calendar, now))
            return bothBlocked(now, "CREDIT_SPREAD_35_COMPLETED_CLOSES_INVALID",
                closes == null ? 0 : closes.size());
        return List.of(one(true, now, closes), one(false, now, closes));
    }


    /**
     * One strictly historical V108 broker-margin / genuine option bid-ask
     * source pass, with no sibling source query and no broker order.
     */
    public FnoFuturesResearchService.StrategyStatus evaluateOne(
            String strategyId, Instant asOf) {
        if (asOf==null) throw new IllegalArgumentException("CREDIT_SPREAD_ASOF_MISSING");
        boolean bullish;
        if(FnoBullPutCreditSpreadV1.STRATEGY_ID.equals(strategyId)) bullish=true;
        else if(FnoBearCallCreditSpreadV1.STRATEGY_ID.equals(strategyId)) bullish=false;
        else throw new IllegalArgumentException("HISTORICAL_CREDIT_STRATEGY_UNSUPPORTED");
        if(!dailyRights || !optionRights || !contractRights || !spreadRights)
            return blocked(bullish,asOf,"CREDIT_SPREAD_SOURCE_RIGHTS_UNVERIFIED",true,0);
        if(dailyStore==null || spreadStore==null || calendar==null)
            return blocked(bullish,asOf,"CREDIT_SPREAD_TYPED_SOURCE_MISSING",false,0);
        if(!calendar.isInsideCashSession(asOf))
            return blocked(bullish,asOf,"CREDIT_SPREAD_CASH_EXCHANGE_SESSION_CLOSED",false,0);
        if(!creditAccountScope.matches("[A-Za-z0-9_-]{8,80}"))
            return blocked(bullish,asOf,"CREDIT_SPREAD_ACCOUNT_OR_MARGIN_NOT_CONFIGURED",false,0);
        String lower=bullish?bullLower:bearLower, upper=bullish?bullUpper:bearUpper;
        if(!validKeys(lower,upper))
            return blocked(bullish,asOf,"CREDIT_SPREAD_REAL_TWO_LEG_KEYS_NOT_CONFIGURED",false,0);
        final List<VerifiedClose> closes;
        try {
            closes=dailyStore.verifiedRecentCloses(asOf);
        } catch(RuntimeException unavailable) {
            return blocked(bullish,asOf,"CREDIT_SPREAD_DAILY_SOURCE_UNAVAILABLE",false,0);
        }
        if(!FnoOptionsResearchService.validHistory(closes,calendar,asOf))
            return blocked(bullish,asOf,"CREDIT_SPREAD_35_COMPLETED_CLOSES_INVALID",
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
        String kind = bullish ? "BULL_PUT" : "BEAR_CALL";
        if (!validKeys(lower, upper)) return blocked(bullish, now,
            "CREDIT_SPREAD_REAL_TWO_LEG_KEYS_NOT_CONFIGURED", false, closes.size());
        final Optional<Proof> proof;
        try {
            proof = spreadStore.oneCurrentSpread(creditAccountScope, kind, lower, upper, now);
        } catch (RuntimeException unavailable) {
            return blocked(bullish, now,
                "CREDIT_SPREAD_SOURCE_READ_UNAVAILABLE", false, closes.size());
        }
        if (proof == null || proof.isEmpty()) return blocked(bullish, now,
            "CREDIT_SPREAD_TWO_LEG_QUOTES_COSTS_OR_DEPTH_PROOF_MISSING",
            false, closes.size());
        var p = proof.get();
        if (!creditAccountScope.equals(p.accountScope())
                || !valid(p, closes.getLast(), kind, lower, upper, now))
            return blocked(bullish, now,
                "CREDIT_SPREAD_PROVIDER_CONTRACT_OR_FIRST_KNOWN_MISMATCH",
                false, closes.size());
        // Margin, existing hedges and possible assignment are independent
        // account evidence; modeled credit risk never proves broker capacity.
        var capitalProof = p.capital();
        if (capitalProof == null || capitalProof.availableCashInr() == null || capitalProof.initialMarginInr() == null
                || capitalProof.variationMarginInr() == null || capitalProof.settlementBufferInr() == null
                || capitalProof.stressedMaxLossInr() == null
                || capitalProof.initialMarginInr().signum() <= 0 || capitalProof.variationMarginInr().signum() <= 0
                || capitalProof.settlementBufferInr().signum() <= 0 || capitalProof.stressedMaxLossInr().signum() <= 0
                || capitalProof.availableCashInr().compareTo(
                    capitalProof.initialMarginInr().add(capitalProof.variationMarginInr())
                        .add(capitalProof.settlementBufferInr())) < 0) {
            return blocked(bullish, now, "CREDIT_SPREAD_BROKER_MARGIN_RESERVES_INSUFFICIENT",
                false, closes.size());
        }
        if (bullish) {
            var bars = new ArrayList<FnoBullPutCreditSpreadV1.CompletedClose>();
            for (var c : closes) bars.add(new FnoBullPutCreditSpreadV1.CompletedClose(
                c.session(), c.provider(), c.verifiedSessionCloseAt(),
                c.firstKnownAt(), c.close(), true));
            var lowerContract = new FnoBullPutCreditSpreadV1.Contract(
                p.lower().key(), NIFTY, "PE", p.lower().strike(),
                p.lower().tickSize(), p.lower().listedFrom(), p.lower().expiry(),
                p.lower().lotSize(), true);
            var upperContract = new FnoBullPutCreditSpreadV1.Contract(
                p.higher().key(), NIFTY, "PE", p.higher().strike(),
                p.higher().tickSize(), p.higher().listedFrom(), p.higher().expiry(),
                p.higher().lotSize(), true);
            var underlying = new FnoBullPutCreditSpreadV1.UnderlyingQuote(
                p.provider(), NIFTY, p.index().price(),
                p.index().exchangeAt(), p.index().firstKnownAt());
            var longQuote = bullQuote(p.lower(), p.provider());
            var shortQuote = bullQuote(p.higher(), p.provider());
            var r = p.risk();
            var assumptions = new FnoBullPutCreditSpreadV1.Assumptions(
                r.buySlippageBps(),r.sellSlippageBps(),r.feesInr(),
                r.maxEachLegSpreadBps(),r.minDepthLots(),r.minOiLots(),
                r.minRewardRiskBps(),r.capitalInr(),r.maxAllocationBps(),
                r.maxStrikeDistanceBps());
            var result = FnoBullPutCreditSpreadV1.evaluate(
                lowerContract, upperContract, List.copyOf(bars),
                underlying, longQuote, shortQuote,
                new FnoBullPutCreditSpreadV1.SourceRights(true,true,true),
                assumptions, now);
            if (result.status() == FnoBullPutCreditSpreadV1.Status.RESEARCH_CANDIDATE
                    && (result.maxLossPerSpreadLotInr() == null
                        || result.maxLossPerSpreadLotInr().compareTo(
                            capitalProof.stressedMaxLossInr()) > 0)) {
                return blocked(true, now, "CREDIT_SPREAD_STRESSED_LOSS_EXCEEDS_RECONCILED_CAP",
                    false, bars.size());
            }
            return status(result.strategyId(), result.status().name(),
                result.reasonCode(), now, p.provider(), bars.size(),
                "VERIFIED_TWO_LEG_PE_CREDIT_SPREAD_RESEARCH_NO_ORDER");
        }

        var bars = new ArrayList<FnoBearCallCreditSpreadV1.CompletedClose>();
        for (var c : closes) bars.add(new FnoBearCallCreditSpreadV1.CompletedClose(
            c.session(), c.provider(), c.verifiedSessionCloseAt(),
            c.firstKnownAt(), c.close(), true));
        var lowerContract = new FnoBearCallCreditSpreadV1.Contract(
            p.lower().key(), NIFTY, "CE", p.lower().strike(),
            p.lower().tickSize(), p.lower().listedFrom(), p.lower().expiry(),
            p.lower().lotSize(), true);
        var upperContract = new FnoBearCallCreditSpreadV1.Contract(
            p.higher().key(), NIFTY, "CE", p.higher().strike(),
            p.higher().tickSize(), p.higher().listedFrom(), p.higher().expiry(),
            p.higher().lotSize(), true);
        var underlying = new FnoBearCallCreditSpreadV1.UnderlyingQuote(
            p.provider(), NIFTY, p.index().price(), p.index().exchangeAt(),
            p.index().firstKnownAt());
        var longQuote = bearQuote(p.higher(), p.provider());
        var shortQuote = bearQuote(p.lower(), p.provider());
        var r = p.risk();
        var assumptions = new FnoBearCallCreditSpreadV1.Assumptions(
            r.buySlippageBps(),r.sellSlippageBps(),r.feesInr(),
            r.maxEachLegSpreadBps(),r.minDepthLots(),r.minOiLots(),
            r.minRewardRiskBps(),r.capitalInr(),r.maxAllocationBps(),
            r.maxStrikeDistanceBps());
        var result = FnoBearCallCreditSpreadV1.evaluate(
            lowerContract, upperContract, List.copyOf(bars),
            underlying, longQuote, shortQuote,
            new FnoBearCallCreditSpreadV1.SourceRights(true,true,true),
            assumptions, now);
        if (result.status() == FnoBearCallCreditSpreadV1.Status.RESEARCH_CANDIDATE
                && (result.maxLossPerSpreadLotInr() == null
                    || result.maxLossPerSpreadLotInr().compareTo(
                        capitalProof.stressedMaxLossInr()) > 0)) {
            return blocked(false, now, "CREDIT_SPREAD_STRESSED_LOSS_EXCEEDS_RECONCILED_CAP",
                false, bars.size());
        }
        return status(result.strategyId(), result.status().name(),
            result.reasonCode(), now, p.provider(), bars.size(),
            "VERIFIED_TWO_LEG_CE_CREDIT_SPREAD_RESEARCH_NO_ORDER");
    }

    private static FnoBullPutCreditSpreadV1.OptionQuote bullQuote(Leg q, String provider) {
        return new FnoBullPutCreditSpreadV1.OptionQuote(provider, q.key(),
            q.bid(), q.ask(), q.bidDepthLots(), q.askDepthLots(),
            q.openInterestLots(), q.exchangeAt(), q.firstKnownAt());
    }
    private static FnoBearCallCreditSpreadV1.OptionQuote bearQuote(Leg q, String provider) {
        return new FnoBearCallCreditSpreadV1.OptionQuote(provider, q.key(),
            q.bid(), q.ask(), q.bidDepthLots(), q.askDepthLots(),
            q.openInterestLots(), q.exchangeAt(), q.firstKnownAt());
    }

    private static boolean valid(Proof p, VerifiedClose last,
            String side, String lowerKey, String upperKey, Instant now) {
        if (p == null || last == null || p.accountScope() == null
                || !NIFTY.equals(p.underlyingKey())
                || !p.provider().equals(last.provider()) || !side.equals(p.spreadType())
                || p.lower() == null || p.higher() == null || p.index() == null
                || p.risk() == null || !lowerKey.equals(p.lower().key())
                || !upperKey.equals(p.higher().key())
                || !p.lower().kind().equals(side.equals("BULL_PUT") ? "PE" : "CE")
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
            ? FnoBullPutCreditSpreadV1.evaluate(null,null,List.of(),null,null,null,
                new FnoBullPutCreditSpreadV1.SourceRights(!denied,!denied,!denied),
                null,now).status().name()
            : FnoBearCallCreditSpreadV1.evaluate(null,null,List.of(),null,null,null,
                new FnoBearCallCreditSpreadV1.SourceRights(!denied,!denied,!denied),
                null,now).status().name();
        return new FnoFuturesResearchService.StrategyStatus(
            bull ? FnoBullPutCreditSpreadV1.STRATEGY_ID
                 : FnoBearCallCreditSpreadV1.STRATEGY_ID,
            type, reason, now, "NONE", 0, 0, sessions, "CREDIT_SPREAD_SOURCE_BLOCKED");
    }

    private static FnoFuturesResearchService.StrategyStatus status(
            String id, String result, String reason, Instant now, String provider,
            int sessions, String inputState) {
        return new FnoFuturesResearchService.StrategyStatus(id, result, reason,
            now, provider, 2, 3, sessions, inputState);
    }
}
