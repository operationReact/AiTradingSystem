package in.aitradingsystem.tradingrooms;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Reuse the unchanged original rights-joined STR-183/184 debit spread source
 * and real Java evaluators at a HISTORICAL decision instant. No source
 * timestamp is constructed from a session label, quote cache, current LTP
 * or broker account. Source access is independently gated by the existing
 * FnoDebitSpreadResearchService -> original completed NIFTY and two-leg
 * V116 rights/model-inference store. This is NOT a performance backtest.
 */
@Service
public final class Fno183184HistoricalDebitSpreadReplayService {
    private static final String BULL=FnoBullCallDebitSpreadV1.STRATEGY_ID;
    private static final String BEAR=FnoBearPutDebitSpreadV1.STRATEGY_ID;
    private final FnoDebitSpreadResearchService source;
    private final Clock clock;

    @Autowired
    public Fno183184HistoricalDebitSpreadReplayService(FnoDebitSpreadResearchService source){
        this(source,Clock.systemUTC());
    }
    Fno183184HistoricalDebitSpreadReplayService(
        FnoDebitSpreadResearchService source,Clock clock){
        this.source=Objects.requireNonNull(source);
        this.clock=Objects.requireNonNull(clock);
    }

    public record Result(String strategyId,String state,String reason,
        int completedIndexSessions,int contractLegs,int originalQuoteLegs,
        boolean independentlyVerifiedHistoricalE2E,
        boolean paperLedgerE2E,boolean liveBrokerE2E,
        boolean brokerOrdersPlaced) {}

    public Result inspect(String strategyId,Instant asOf){
        if(!List.of(BULL,BEAR).contains(strategyId)||asOf==null||
           asOf.isAfter(clock.instant())||
           asOf.isBefore(clock.instant().minus(Duration.ofDays(366))))
            return blocked(strategyId,"DEBIT_SPREAD_HISTORICAL_SCOPE_INVALID");
        final List<FnoFuturesResearchService.StrategyStatus> rows;
        try{
            // Existing source independently joins original NIFTY 35+ EOD
            // first-known candles to separately entitled two-leg true bid/ask,
            // OI/depth, listed strikes and after-tax estimated costs at asOf.
            // It NEVER sends broker/PAPER/LIVE orders.
            rows=source.evaluateAll(asOf);
        }catch(RuntimeException sourceFailure){
            return blocked(strategyId,"DEBIT_SPREAD_ORIGINAL_SOURCE_UNAVAILABLE");
        }
        if(rows==null||rows.size()!=2||rows.get(0)==null||rows.get(1)==null
                ||!BULL.equals(rows.get(0).strategyId())
                ||!BEAR.equals(rows.get(1).strategyId()))
            return blocked(strategyId,"DEBIT_SPREAD_TYPED_SOURCE_PAIR_INCOMPLETE");
        for(var row:rows){
            if(row.evaluatedAt()==null||!asOf.equals(row.evaluatedAt())||
               row.status()==null||row.reasonCode()==null||
               !row.reasonCode().matches("[A-Z0-9_]{3,128}")||
               row.contractsResolved()<0||row.contractsResolved()>2||
               row.freshQuotes()<0||row.freshQuotes()>3||
               row.historySessions()<0||row.historySessions()>512)
                return blocked(strategyId,"DEBIT_SPREAD_ORIGINAL_RESULT_INVALID");
        }
        var selected=BULL.equals(strategyId)?rows.get(0):rows.get(1);
        boolean research="RESEARCH_CANDIDATE".equals(selected.status())||
            "NO_SIGNAL".equals(selected.status());
        if(research&&(selected.historySessions()<35||
                      selected.contractsResolved()!=2||
                      selected.freshQuotes()!=3))
            return blocked(strategyId,"DEBIT_SPREAD_SOURCE_PROOF_COUNTS_MISSING");
        if(!research&&!selected.status().startsWith("BLOCKED"))
            return blocked(strategyId,"DEBIT_SPREAD_UNRECOGNIZED_EVALUATOR_STATUS");

        return new Result(strategyId,
            research?"ORIGINAL_DEBIT_SPREAD_EVALUATOR_RESEARCH_ONLY":
                     "BLOCKED_NOT_HISTORICAL_E2E",
            selected.reasonCode(),selected.historySessions(),
            selected.contractsResolved(),selected.freshQuotes(),
            false,false,false,false);
    }
    private static Result blocked(String id,String reason){
        return new Result(id,"BLOCKED_NOT_HISTORICAL_E2E",
            reason,0,0,0,false,false,false,false);
    }
}
