package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyCreditSpreadSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Original source/custody-gated historical STR-185/186 research only.
 *
 * <p>Reuses the unchanged FnoCreditSpreadResearchService, which requires
 * original V106/V109 completed index + two-option source rights and genuine
 * broker-account-specific, as-of reconciled margin, variation, assignment,
 * settlement buffer, existing positions and stressed-loss evidence. The
 * server-side account scope is never accepted from the HTTP request.
 *
 * <p>Never creates orders, assumes margin from today's balance, grants source
 * rights or converts hypothetical expiry spreads into accepted historical
 * after-cost OOS and fill evidence.
 */
@Service
public class Fno185186VerifiedHistoricalReplayService {
    private static final String BULL = FnoBullPutCreditSpreadV1.STRATEGY_ID;
    private static final String BEAR = FnoBearCallCreditSpreadV1.STRATEGY_ID;
    private static final String CONTRACT_KEY = "NSE_FO\\|[A-Za-z0-9._-]{1,64}";
    private final FnoVerifiedNiftyLongOptionSourceStore daily;
    private final FnoVerifiedNiftyCreditSpreadSourceStore source;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean dailyRights, optionRights, discoveryRights, creditRights;
    private final String accountScope;

    @Autowired
    public Fno185186VerifiedHistoricalReplayService(
            FnoVerifiedNiftyLongOptionSourceStore daily,
            FnoVerifiedNiftyCreditSpreadSourceStore source,
            MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.nifty-daily-close-research-rights-verified:false}")
            boolean dailyRights,
            @Value("${minttick.fno-research.long-option-quote-research-rights-verified:false}")
            boolean optionRights,
            @Value("${minttick.fno-research.long-option-contract-discovery-verified:false}")
            boolean discoveryRights,
            @Value("${minttick.fno-research.credit-spread-research-rights-verified:false}")
            boolean creditRights,
            @Value("${minttick.fno-research.credit-spread-account-scope:}")
            String accountScope) {
        this(daily,source,calendar,Clock.systemUTC(),dailyRights,
            optionRights,discoveryRights,creditRights,accountScope);
    }

    Fno185186VerifiedHistoricalReplayService(
            FnoVerifiedNiftyLongOptionSourceStore daily,
            FnoVerifiedNiftyCreditSpreadSourceStore source,
            MarketSessionCalendar calendar, Clock clock, boolean dailyRights,
            boolean optionRights, boolean discoveryRights,
            boolean creditRights, String accountScope) {
        this.daily=Objects.requireNonNull(daily);
        this.source=Objects.requireNonNull(source);
        this.calendar=Objects.requireNonNull(calendar);
        this.clock=Objects.requireNonNull(clock);
        this.dailyRights=dailyRights;
        this.optionRights=optionRights;
        this.discoveryRights=discoveryRights;
        this.creditRights=creditRights;
        this.accountScope=accountScope==null?"":accountScope.trim();
    }

    public record Report(
        String strategyId, String state, String reasonCode,
        int verifiedCompletedNiftySessions, int verifiedOptionContracts,
        boolean historicalBrokerMarginProofConsidered,
        boolean independentHistoricalE2E, boolean paperLedgerE2E,
        boolean liveBrokerE2E, boolean brokerOrdersPlaced) {}

    public Report inspect(String strategyId, String lowerKey,
                          String higherKey, Instant asOf) {
        if (!BULL.equals(strategyId) && !BEAR.equals(strategyId))
            return denied(strategyId,"CREDIT_SPREAD_HISTORICAL_STRATEGY_UNSUPPORTED");
        if (!dailyRights || !optionRights || !discoveryRights || !creditRights)
            return denied(strategyId,"ORIGINAL_CREDIT_SPREAD_SOURCE_RIGHTS_UNVERIFIED");
        if (!accountScope.matches("[A-Za-z0-9_-]{8,80}"))
            return denied(strategyId,"HISTORICAL_BROKER_ACCOUNT_MARGIN_SCOPE_UNAVAILABLE");
        if (lowerKey==null || !lowerKey.matches(CONTRACT_KEY)
                || higherKey==null || !higherKey.matches(CONTRACT_KEY)
                || lowerKey.equals(higherKey) || asOf==null
                || asOf.isAfter(clock.instant())
                || asOf.isBefore(clock.instant().minus(Duration.ofDays(366)))
                || !calendar.isInsideCashSession(asOf))
            return denied(strategyId,"CREDIT_SPREAD_HISTORICAL_ASOF_SCOPE_INVALID");

        boolean bullish=BULL.equals(strategyId);
        // The original production evaluator's broker margin and stressed-loss
        // gates remain mandatory, including account-specific V109 source data.
        var evaluator=new FnoCreditSpreadResearchService(
            daily,source,calendar,
            true,true,true,true,accountScope,
            bullish?lowerKey:"",
            bullish?higherKey:"",
            bullish?"":lowerKey,
            bullish?"":higherKey);
        final List<FnoFuturesResearchService.StrategyStatus> results;
        try {
            results=evaluator.evaluateAll(asOf);
        } catch (RuntimeException unavailable) {
            return denied(strategyId,"HISTORICAL_CREDIT_SPREAD_SOURCE_UNAVAILABLE");
        }
        var one=results.stream().filter(x->strategyId.equals(x.strategyId()))
            .findFirst().orElse(null);
        if(one==null)
            return denied(strategyId,"HISTORICAL_CREDIT_SPREAD_EVALUATOR_MISSING");
        String expected=bullish
            ? "VERIFIED_TWO_LEG_PE_CREDIT_SPREAD_RESEARCH_NO_ORDER"
            : "VERIFIED_TWO_LEG_CE_CREDIT_SPREAD_RESEARCH_NO_ORDER";
        boolean complete=expected.equals(one.inputState())
            && !one.status().startsWith("BLOCKED");
        return new Report(strategyId,
            complete?"ORIGINAL_BROKER_MARGIN_SOURCE_RESEARCH_ONLY_NOT_E2E"
                :"BLOCKED_NOT_HISTORICAL_E2E",
            one.reasonCode(),complete?one.historySessions():0,
            complete?2:0,complete,
            false,false,false,false);
    }

    private static Report denied(String id,String reason){
        return new Report(id,"BLOCKED_NOT_HISTORICAL_E2E",reason,
            0,0,false,false,false,false,false);
    }
}
