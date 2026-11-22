package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyDebitSpreadSourceStore;
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
 * Strict as-of, original-rights-joined NIFTY debit spread historical reader.
 *
 * <p>Reuses the ORIGINAL unchanged STR-183/184 evaluator service for genuine
 * underlying+two-option source windows. Its V106/V108 PostgreSQL readers
 * require verified MODEL_INFERENCE rights, 35 completed source-known NIFTY
 * sessions, two original quoted options, genuine full-lot depth/OI,
 * original exchange/first-known times, and separately source-checked fees.
 * A successful structural research decision is NOT accepted market history
 * E2E or permission to place a two-leg broker order.
 */
@Service
public class Fno183184VerifiedHistoricalReplayService {
    private static final String BULL = FnoBullCallDebitSpreadV1.STRATEGY_ID;
    private static final String BEAR = FnoBearPutDebitSpreadV1.STRATEGY_ID;
    private static final String KEY = "NSE_FO\\|[A-Za-z0-9._-]{1,64}";
    private final FnoVerifiedNiftyLongOptionSourceStore daily;
    private final FnoVerifiedNiftyDebitSpreadSourceStore spreads;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean dailyRights, optionRights, discoveryRights, spreadRights;

    @Autowired
    public Fno183184VerifiedHistoricalReplayService(
            FnoVerifiedNiftyLongOptionSourceStore daily,
            FnoVerifiedNiftyDebitSpreadSourceStore spreads,
            MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.nifty-daily-close-research-rights-verified:false}")
            boolean dailyRights,
            @Value("${minttick.fno-research.long-option-quote-research-rights-verified:false}")
            boolean optionRights,
            @Value("${minttick.fno-research.long-option-contract-discovery-verified:false}")
            boolean discoveryRights,
            @Value("${minttick.fno-research.debit-spread-research-rights-verified:false}")
            boolean spreadRights) {
        this(daily, spreads, calendar, Clock.systemUTC(),
            dailyRights,optionRights,discoveryRights,spreadRights);
    }

    Fno183184VerifiedHistoricalReplayService(
            FnoVerifiedNiftyLongOptionSourceStore daily,
            FnoVerifiedNiftyDebitSpreadSourceStore spreads,
            MarketSessionCalendar calendar, Clock clock,
            boolean dailyRights, boolean optionRights,
            boolean discoveryRights, boolean spreadRights) {
        this.daily=Objects.requireNonNull(daily);
        this.spreads=Objects.requireNonNull(spreads);
        this.calendar=Objects.requireNonNull(calendar);
        this.clock=Objects.requireNonNull(clock);
        this.dailyRights=dailyRights;
        this.optionRights=optionRights;
        this.discoveryRights=discoveryRights;
        this.spreadRights=spreadRights;
    }

    public record Report(
        String strategyId, String state, String evaluatorReasonCode,
        int verifiedDailySourceSessions, int verifiedOptionContracts,
        boolean independentHistoricalE2E, boolean paperLedgerE2E,
        boolean liveBrokerE2E, boolean brokerOrdersPlaced) {}

    public Report inspect(String strategyId, String lowerOptionKey,
                          String higherOptionKey, Instant asOf) {
        if (!BULL.equals(strategyId) && !BEAR.equals(strategyId))
            return denied(strategyId, "HISTORICAL_DEBIT_SPREAD_STRATEGY_UNSUPPORTED");
        if (!dailyRights || !optionRights || !discoveryRights || !spreadRights)
            return denied(strategyId, "ORIGINAL_DEBIT_SPREAD_SOURCE_RIGHTS_NOT_VERIFIED");
        if (lowerOptionKey == null || !lowerOptionKey.matches(KEY)
                || higherOptionKey == null || !higherOptionKey.matches(KEY)
                || lowerOptionKey.equals(higherOptionKey) || asOf == null
                || asOf.isAfter(clock.instant())
                || asOf.isBefore(clock.instant().minus(Duration.ofDays(366)))
                || !calendar.isInsideCashSession(asOf))
            return denied(strategyId, "ORIGINAL_DEBIT_SPREAD_ASOF_SCOPE_INVALID");

        // Delegate the whole source-and-evaluator path to the already-tested
        // production STR-183/184 research service, scoped to ONE exact
        // contract pair. The other strategy's keys are intentionally blank.
        boolean bullish=BULL.equals(strategyId);
        var adapter=new FnoDebitSpreadResearchService(
            daily,spreads,calendar,
            true,true,true,true,
            bullish ? lowerOptionKey : "",
            bullish ? higherOptionKey : "",
            bullish ? "" : lowerOptionKey,
            bullish ? "" : higherOptionKey);
        final List<FnoFuturesResearchService.StrategyStatus> evaluated;
        try {
            evaluated=adapter.evaluateAll(asOf);
        } catch (RuntimeException unavailable) {
            // No SQL, account data, provider response, or credentials in output.
            return denied(strategyId, "ORIGINAL_DEBIT_SPREAD_SOURCE_UNAVAILABLE");
        }
        var selection=evaluated.stream()
            .filter(row->strategyId.equals(row.strategyId()))
            .findFirst().orElse(null);
        if (selection == null)
            return denied(strategyId, "ORIGINAL_DEBIT_SPREAD_EVALUATOR_MISSING");

        boolean originalProofPresent=bullish
            ? "VERIFIED_TWO_LEG_CE_DEBIT_SPREAD_RESEARCH_NO_ORDER".equals(
                selection.inputState())
            : "VERIFIED_TWO_LEG_PE_DEBIT_SPREAD_RESEARCH_NO_ORDER".equals(
                selection.inputState());
        int verifiedSessions=originalProofPresent ? selection.historySessions() : 0;
        int verifiedContracts=originalProofPresent ? 2 : 0;
        return new Report(strategyId,
            originalProofPresent && !selection.status().startsWith("BLOCKED")
                ? "ORIGINAL_SOURCE_RESEARCH_EVALUATOR_RAN_NOT_E2E"
                : "BLOCKED_NOT_HISTORICAL_E2E",
            selection.reasonCode(),verifiedSessions,verifiedContracts,
            false,false,false,false);
    }

    private static Report denied(String strategy, String reason) {
        return new Report(strategy,"BLOCKED_NOT_HISTORICAL_E2E",
            reason,0,0,false,false,false,false);
    }
}
