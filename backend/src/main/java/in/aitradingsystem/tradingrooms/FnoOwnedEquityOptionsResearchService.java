package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedEquityOwnedOptionStore;
import in.aitradingsystem.marketdata.FnoVerifiedEquityOwnedOptionStore.VerifiedClose;
import in.aitradingsystem.marketdata.FnoVerifiedEquityOwnedOptionStore.Option;
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
 * Research-only STR-187 Protective Put and STR-188 Covered Call with actual
 * broker-reconciled *settled and deliverable* equity shares. A watchlist, paper
 * holding or a broker login never proves owned full-lot NSE stock.
 */
@Service
public final class FnoOwnedEquityOptionsResearchService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final FnoVerifiedEquityOwnedOptionStore source;
    private final MarketSessionCalendar calendar;
    private final boolean historyRights, holdingsRights, chainRights, masterRights, feeRights;
    private final String accountScope, stockKey, putKey, callKey;

    @Autowired
    public FnoOwnedEquityOptionsResearchService(
            FnoVerifiedEquityOwnedOptionStore source, MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.owned-equity-close-rights-verified:false}")
                boolean historyRights,
            @Value("${minttick.fno-research.settled-holdings-research-rights-verified:false}")
                boolean holdingsRights,
            @Value("${minttick.fno-research.stock-option-chain-research-rights-verified:false}")
                boolean chainRights,
            @Value("${minttick.fno-research.stock-option-master-research-rights-verified:false}")
                boolean masterRights,
            @Value("${minttick.fno-research.owned-stock-option-fee-rights-verified:false}")
                boolean feeRights,
            @Value("${minttick.fno-research.owned-stock-account-scope:}") String accountScope,
            @Value("${minttick.fno-research.owned-stock-equity-key:}") String stockKey,
            @Value("${minttick.fno-research.owned-stock-pe-key:}") String putKey,
            @Value("${minttick.fno-research.owned-stock-ce-key:}") String callKey) {
        this.source = source;
        this.calendar = calendar;
        this.historyRights = historyRights;
        this.holdingsRights = holdingsRights;
        this.chainRights = chainRights;
        this.masterRights = masterRights;
        this.feeRights = feeRights;
        this.accountScope = accountScope == null ? "" : accountScope.trim();
        this.stockKey = stockKey == null ? "" : stockKey.trim();
        this.putKey = putKey == null ? "" : putKey.trim();
        this.callKey = callKey == null ? "" : callKey.trim();
    }

    public List<FnoFuturesResearchService.StrategyStatus> evaluateAll(Instant at) {
        if (at == null) throw new IllegalArgumentException("OWNED_STOCK_ASOF_MISSING");
        if (!historyRights || !holdingsRights || !chainRights || !masterRights || !feeRights)
            return both(at, "OWNED_STOCK_SOURCE_RIGHTS_UNVERIFIED", true, 0);
        if (source == null || calendar == null)
            return both(at, "OWNED_STOCK_SOURCE_UNAVAILABLE", false, 0);
        if (!calendar.isInsideCashSession(at))
            return both(at, "OWNED_STOCK_EXCHANGE_SESSION_CLOSED", false, 0);
        if (!accountScope.matches("[A-Za-z0-9_-]{8,80}")
                || !stockKey.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}"))
            return both(at, "OWNED_STOCK_ACCOUNT_OR_EQUITY_NOT_CONFIGURED", false, 0);
        if (!validOptionKey(putKey) && !validOptionKey(callKey))
            return both(at, "OWNED_STOCK_PUT_CALL_NOT_CONFIGURED", false, 0);

        final List<VerifiedClose> history;
        try { history = source.readCloses(stockKey, at); }
        catch (RuntimeException unavailable) {
            return both(at, "OWNED_STOCK_HISTORY_SOURCE_UNAVAILABLE", false, 0);
        }
        if (!validHistory(history, at))
            return both(at, "OWNED_STOCK_35_VERIFIED_CLOSES_MISSING",
                false, history == null ? 0 : history.size());
        return List.of(one(true, history, at), one(false, history, at));
    }

    private List<FnoFuturesResearchService.StrategyStatus> both(
            Instant at, String code, boolean denied, int bars) {
        return List.of(blocked(true, at, code, denied, bars),
                       blocked(false, at, code, denied, bars));
    }

    private FnoFuturesResearchService.StrategyStatus one(
            boolean put, List<VerifiedClose> bars, Instant at) {
        String key = put ? putKey : callKey;
        if (!validOptionKey(key))
            return blocked(put, at, "OWNED_STOCK_PUT_CALL_NOT_CONFIGURED", false, bars.size());
        final Optional<Option> proof;
        try { proof = source.oneSettledLot(accountScope, stockKey, key,
                put ? "PE" : "CE", at); }
        catch (RuntimeException unavailable) {
            return blocked(put, at, "OWNED_STOCK_HOLDINGS_OR_OPTION_READ_UNAVAILABLE",
                false, bars.size());
        }
        if (proof == null || proof.isEmpty())
            return blocked(put, at, "OWNED_STOCK_BROKER_SETTLED_LOT_PROOF_MISSING",
                false, bars.size());
        var p = proof.get();
        if (!validProof(p, bars.getLast(), key, put ? "PE" : "CE", at))
            return blocked(put, at, "OWNED_STOCK_SOURCE_CONTRACT_OR_ACCOUNT_MISMATCH",
                false, bars.size());
        if (put) {
            var completed = new ArrayList<FnoProtectivePutV1.CompletedClose>();
            for (var c : bars) completed.add(new FnoProtectivePutV1.CompletedClose(
                c.session(), c.provider(), c.close(),
                c.verifiedSessionCloseAt(), c.firstKnownAt(), true));
            var contract = new FnoProtectivePutV1.PutContract(
                key, stockKey, "PE", p.strike(), p.tickSize(), p.listedFrom(),
                p.expiry(), p.lotSize(), true, true);
            var holding = new FnoProtectivePutV1.Holding(
                p.provider(), stockKey, p.holding().settledOwnedShares(),
                p.holding().acquisitionCost(), p.holding().observedAt(), true, true);
            var equity = new FnoProtectivePutV1.EquityQuote(
                p.provider(), stockKey, p.equityPrice(),
                p.equityExchangeAt(), p.equityFirstKnownAt());
            var quote = new FnoProtectivePutV1.PutQuote(
                p.provider(), key, p.bid(), p.ask(), p.askDepthLots(),
                p.oiLots(), p.optionExchangeAt(), p.optionFirstKnownAt());
            var cost = new FnoProtectivePutV1.Assumptions(
                p.expiryMoveBps(), p.ownedTriggerBps(), p.slippageBps(),
                p.maxSpreadBps(), p.minDepthLots(), p.minOiLots(),
                p.maxStrikeDistanceBps(), p.allInFeesInr(), p.capitalInr(),
                p.maxCapitalAllocationBps(), p.minimumBenefitInr());
            var r = FnoProtectivePutV1.evaluate(contract, holding, List.copyOf(completed),
                equity, quote, new FnoProtectivePutV1.SourceRights(true,true,true,true),
                cost, at);
            return status(r.strategyId(),r.status().name(),r.reasonCode(),at,
                p.provider(),bars.size(),"SETTLED_EQUITY_PROTECTIVE_PUT_RESEARCH_NO_ORDER");
        }
        var completed = new ArrayList<FnoCoveredCallV1.CompletedClose>();
        for (var c : bars) completed.add(new FnoCoveredCallV1.CompletedClose(
            c.session(), c.provider(), c.close(),
            c.verifiedSessionCloseAt(), c.firstKnownAt(), true));
        var contract = new FnoCoveredCallV1.CallContract(
            key, stockKey, "CE", p.strike(), p.tickSize(), p.listedFrom(),
            p.expiry(), p.lotSize(), true, true);
        var holding = new FnoCoveredCallV1.Holding(
            p.provider(), stockKey, p.holding().settledOwnedShares(),
            p.holding().acquisitionCost(), p.holding().observedAt(), true, true);
        var equity = new FnoCoveredCallV1.EquityQuote(
            p.provider(), stockKey, p.equityPrice(),
            p.equityExchangeAt(), p.equityFirstKnownAt());
        var quote = new FnoCoveredCallV1.CallQuote(
            p.provider(), key, p.bid(), p.ask(), p.bidDepthLots(),
            p.oiLots(), p.optionExchangeAt(), p.optionFirstKnownAt());
        var cost = new FnoCoveredCallV1.Assumptions(
            p.expiryMoveBps(), p.ownedTriggerBps(), p.slippageBps(),
            p.maxSpreadBps(), p.minDepthLots(), p.minOiLots(),
            p.maxStrikeDistanceBps(), p.allInFeesInr(), p.capitalInr(),
            p.maxCapitalAllocationBps(), p.minimumBenefitInr());
        var r = FnoCoveredCallV1.evaluate(contract, holding, List.copyOf(completed),
            equity, quote, new FnoCoveredCallV1.SourceRights(true,true,true,true),
            cost, at);
        return status(r.strategyId(),r.status().name(),r.reasonCode(),at,
            p.provider(),bars.size(),"SETTLED_EQUITY_COVERED_CALL_RESEARCH_NO_ORDER");
    }

    private boolean validHistory(List<VerifiedClose> rows, Instant at) {
        if (rows == null || rows.size() < 35 || rows.size() > 90) return false;
        LocalDate previous = null;
        String provider = null;
        for (var b : rows) {
            if (b == null || !stockKey.equals(b.equityKey())
                    || !supported(b.provider()) || b.session() == null
                    || b.close() == null || b.close().signum() <= 0
                    || b.verifiedSessionCloseAt() == null || b.firstKnownAt() == null
                    || !b.verifiedSessionCloseAt().equals(
                        b.session().atTime(15,30).atZone(IST).toInstant())
                    || b.firstKnownAt().isBefore(b.verifiedSessionCloseAt())
                    || b.firstKnownAt().isAfter(at)
                    || b.firstKnownAt().isAfter(
                        b.verifiedSessionCloseAt().plus(Duration.ofHours(36)))
                    || !calendar.isTradingDay(b.session())
                    || (provider != null && !provider.equals(b.provider()))
                    || (previous != null && !previous.isBefore(b.session()))) return false;
            if (previous != null) {
                for (LocalDate d = previous.plusDays(1); d.isBefore(b.session());
                        d = d.plusDays(1)) {
                    if (calendar.isTradingDay(d)) return false;
                }
            }
            previous = b.session();
            provider = b.provider();
        }
        return previous != null && previous.isAfter(at.atZone(IST).toLocalDate().minusDays(5))
            && !previous.isAfter(at.atZone(IST).toLocalDate());
    }

    private boolean validProof(Option q, VerifiedClose last,
            String expectedKey, String side, Instant at) {
        if (q == null || q.holding() == null || !supported(q.provider())
                || !q.provider().equals(last.provider())
                || !accountScope.equals(q.accountScope())
                || !accountScope.equals(q.holding().accountScope())
                || !stockKey.equals(q.equityKey())
                || !stockKey.equals(q.holding().equityKey())
                || !expectedKey.equals(q.optionKey()) || !side.equals(q.kind())
                || q.lotSize() < 1 || q.lotSize() != q.holding().settledOwnedShares()
                || q.listedFrom() == null || q.expiry() == null
                || q.listedFrom().isAfter(at.atZone(IST).toLocalDate())
                || q.expiry().isBefore(at.atZone(IST).toLocalDate().plusDays(3))
                || q.expiry().isAfter(at.atZone(IST).toLocalDate().plusDays(45))
                || q.holding().observedAt() == null
                || q.holding().observedAt().isAfter(at)
                || Duration.between(q.holding().observedAt(),at)
                    .compareTo(Duration.ofMinutes(5)) > 0
                || q.holding().acquisitionCost() == null
                || q.holding().acquisitionCost().signum() <= 0
                || q.strike() == null || q.tickSize() == null
                || q.strike().signum() <= 0 || q.tickSize().signum() <= 0
                || q.strike().remainder(q.tickSize()).signum() != 0
                || q.equityPrice() == null || q.equityPrice().signum() <= 0
                || q.bid() == null || q.ask() == null || q.bid().signum() <= 0
                || q.ask().compareTo(q.bid()) < 0
                || q.equityExchangeAt() == null || q.equityFirstKnownAt() == null
                || q.optionExchangeAt() == null || q.optionFirstKnownAt() == null
                || q.equityExchangeAt().isAfter(q.equityFirstKnownAt())
                || q.optionExchangeAt().isAfter(q.optionFirstKnownAt())
                || q.equityFirstKnownAt().isAfter(at)
                || q.optionFirstKnownAt().isAfter(at)
                || last.firstKnownAt().isAfter(q.equityExchangeAt())
                || last.firstKnownAt().isAfter(q.optionExchangeAt())
                || Duration.between(q.equityExchangeAt(),q.optionExchangeAt())
                    .abs().compareTo(Duration.ofSeconds(90)) > 0
                || q.bidDepthLots() < 1 || q.askDepthLots() < 1
                || q.oiLots() < 1 || q.capitalInr() == null
                || q.capitalInr().signum() <= 0 || q.allInFeesInr() == null
                || q.allInFeesInr().signum() <= 0) return false;
        return true;
    }

    private static boolean validOptionKey(String key) {
        return key != null && key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}");
    }
    private static boolean supported(String provider) {
        return "UPSTOX_ANALYTICS".equals(provider) || "ANGEL_ONE_SMARTAPI".equals(provider);
    }
    private static FnoFuturesResearchService.StrategyStatus blocked(
            boolean put, Instant at, String code, boolean denied, int bars) {
        String state = put
            ? FnoProtectivePutV1.evaluate(null,null,List.of(),null,null,
                new FnoProtectivePutV1.SourceRights(!denied,!denied,!denied,!denied),
                null,at).status().name()
            : FnoCoveredCallV1.evaluate(null,null,List.of(),null,null,
                new FnoCoveredCallV1.SourceRights(!denied,!denied,!denied,!denied),
                null,at).status().name();
        return new FnoFuturesResearchService.StrategyStatus(
            put ? FnoProtectivePutV1.STRATEGY_ID : FnoCoveredCallV1.STRATEGY_ID,
            state, code, at, "NONE", 0, 0, bars, "OWNED_EQUITY_OPTION_SOURCE_BLOCKED");
    }
    private static FnoFuturesResearchService.StrategyStatus status(
            String id, String state, String code, Instant at,
            String provider, int sessions, String source) {
        return new FnoFuturesResearchService.StrategyStatus(
            id, state, code, at, provider, 1, 2, sessions, source);
    }
}
