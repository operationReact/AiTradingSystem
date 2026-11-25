package in.aitradingsystem.tradingrooms;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Historical as-of replay of licensed 200+ source-original NIFTY/30-day IV
 * observations through the unchanged STR-201 research evaluator.
 * FnoIvRankResearchService independently checks four source-use rights,
 * original close/IV as-of, matching source/session calendar and 30-day
 * constant tenor normalization. No quote, raw series, receipt or order
 * is returned to clients. An evaluator candidate is NOT after-cost OOS E2E.
 */
@Service
public final class Fno201VerifiedHistoricalReplayService {
    public static final String STRATEGY=FnoIvRankMeanReversionV1.STRATEGY_ID;
    private final FnoIvRankResearchService research;
    private final Clock clock;

    @Autowired
    public Fno201VerifiedHistoricalReplayService(FnoIvRankResearchService research) {
        this(research, Clock.systemUTC());
    }
    Fno201VerifiedHistoricalReplayService(FnoIvRankResearchService research,Clock clock) {
        this.research=Objects.requireNonNull(research);
        this.clock=Objects.requireNonNull(clock);
    }

    public record Report(String strategyId,String state,String reasonCode,
        int verifiedOriginalSessions,int originalCurrentIvSnapshots,
        boolean independentlyAcceptedHistoricalE2E,boolean paperLedgerE2E,
        boolean liveBrokerE2E,boolean brokerOrdersPlaced) {}

    public Report inspect(Instant asOf) {
        if(asOf==null||asOf.isAfter(clock.instant())||
            asOf.isBefore(clock.instant().minus(Duration.ofDays(366))))
            return blocked("IV_RANK_HISTORICAL_ASOF_SCOPE_INVALID");
        final FnoFuturesResearchService.StrategyStatus result;
        try {
            result=research.evaluate(asOf);
        } catch(RuntimeException unavailable) {
            return blocked("IV_RANK_RIGHTS_JOIN_SOURCE_UNAVAILABLE");
        }
        if(result==null||!STRATEGY.equals(result.strategyId())||
            !asOf.equals(result.evaluatedAt())||
            result.status()==null||result.reasonCode()==null||
            !result.reasonCode().matches("[A-Z0-9_]{3,128}")||
            result.historySessions()<0||result.historySessions()>370||
            result.freshQuotes()<0||result.freshQuotes()>1||
            result.contractsResolved()!=0)
            return blocked("IV_RANK_SOURCE_REPLAY_RESULT_INVALID");

        boolean evaluated="RESEARCH_CANDIDATE".equals(result.status())||
            "NO_SIGNAL".equals(result.status());
        if(evaluated&&(result.historySessions()<200||
            result.freshQuotes()!=1||
            !"ORIGINAL_30_DAY_ATM_IV_200_SOURCE_ALIGNED_CANDLES_NO_ORDER"
                .equals(result.inputState())))
            return blocked("IV_RANK_ORIGINAL_200_SESSION_EVIDENCE_MISSING");
        if(!evaluated&&!result.status().startsWith("BLOCKED"))
            return blocked("IV_RANK_EVALUATOR_STATUS_UNRECOGNIZED");
        return new Report(STRATEGY,evaluated
            ?"ORIGINAL_30_DAY_ATM_IV_EVALUATOR_RESEARCH_ONLY"
            :"BLOCKED_NOT_HISTORICAL_E2E",
            result.reasonCode(),result.historySessions(),result.freshQuotes(),
            false,false,false,false);
    }

    private static Report blocked(String reason){
        return new Report(STRATEGY,"BLOCKED_NOT_HISTORICAL_E2E",reason,
            0,0,false,false,false,false);
    }
}
