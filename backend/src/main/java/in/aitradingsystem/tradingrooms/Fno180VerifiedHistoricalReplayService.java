package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedExpiryBasisPairStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * STR-180 exact V105 source/rights/cost-joined completed cash and futures
 * near-close pairs -> unchanged Java historical basis evaluator.
 * No settlement prices, dividends or publication timestamps are synthesized.
 * This is research-only point-in-time replay, never accepted market backtest
 * performance, broker execution, or a recommendation to trade physical F&O.
 */
@Service
public class Fno180VerifiedHistoricalReplayService {
    public static final String STRATEGY=FnoExpiryBasisReversionV1.STRATEGY_ID;
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private final FnoVerifiedExpiryBasisPairStore source;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean research, basisResearch, discovery;

    @Autowired
    public Fno180VerifiedHistoricalReplayService(
        FnoVerifiedExpiryBasisPairStore source,MarketSessionCalendar calendar,
        @Value("${minttick.fno-research.research-rights-verified:false}") boolean research,
        @Value("${minttick.fno-research.expiry-basis-research-rights-verified:false}") boolean basisResearch,
        @Value("${minttick.fno-research.contract-discovery-verified:false}") boolean discovery) {
        this(source,calendar,Clock.systemUTC(),research,basisResearch,discovery);
    }
    Fno180VerifiedHistoricalReplayService(
        FnoVerifiedExpiryBasisPairStore source,MarketSessionCalendar calendar,
        Clock clock,boolean research,boolean basisResearch,boolean discovery) {
        this.source=Objects.requireNonNull(source);
        this.calendar=Objects.requireNonNull(calendar);
        this.clock=Objects.requireNonNull(clock);
        this.research=research;
        this.basisResearch=basisResearch;
        this.discovery=discovery;
    }

    public record Result(String strategyId,String state,String reason,
        int sourcePairs,int evaluatedPairs,boolean historicalE2EAccepted,
        boolean paperLedgerE2E,boolean liveBrokerE2E,boolean ordersPlaced) {}

    public Result inspect(String spotKey,String futureKey,LocalDate expiry,
                          int lotSize,Instant asOf) {
        if(!research||!basisResearch||!discovery)
            return blocked("STR180_SPOT_FUTURE_CARRY_RIGHTS_UNVERIFIED",0);
        if(spotKey==null||!spotKey.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")||
            futureKey==null||!futureKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")||
            expiry==null||lotSize<1||lotSize>100000||asOf==null||
            asOf.isAfter(clock.instant())||
            asOf.isBefore(clock.instant().minus(Duration.ofDays(366)))||
            ChronoUnit.DAYS.between(asOf.atZone(IST).toLocalDate(),expiry)<3||
            ChronoUnit.DAYS.between(asOf.atZone(IST).toLocalDate(),expiry)>14)
            return blocked("STR180_REPLAY_CONTRACT_OR_SCOPE_INVALID",0);
        LocalDate to=asOf.atZone(IST).toLocalDate().minusDays(1);
        LocalDate from=to.minusDays(20);
        final List<FnoVerifiedExpiryBasisPairStore.PairProof> rows;
        try {
            // SQL joins separately verified NSE_EQ/NSE_FO original source
            // first-known/close/calendar, carry review & MODEL_INFERENCE grants.
            rows=source.readExactPair(spotKey,futureKey,expiry,lotSize,from,to,asOf);
        } catch(RuntimeException inaccessible) {
            return blocked("STR180_ORIGINAL_PAIR_SOURCE_UNAVAILABLE",0);
        }
        if(rows==null||rows.size()<5)
            return blocked("STR180_FIVE_ORIGINAL_PAIRS_MISSING",
                rows==null?0:rows.size());
        if(rows.size()>=40)
            return blocked("STR180_PAIR_SOURCE_AMBIGUOUS_OR_UNBOUNDED",0);

        FnoVerifiedExpiryBasisPairStore.PairProof previous=null;
        for(var row:rows) {
            if(row==null||!spotKey.equals(row.spotKey())||
                !futureKey.equals(row.futuresKey())||!expiry.equals(row.expiry())||
                lotSize!=row.lotSize()||row.sessionDate()==null||
                row.listedFrom()==null||row.sessionDate().isBefore(row.listedFrom())||
                !row.sessionDate().isBefore(expiry)||
                row.provider()==null||!List.of("UPSTOX_ANALYTICS","ANGEL_ONE_SMARTAPI")
                    .contains(row.provider())||
                !row.deliverableSpotVerified()||row.assumptions()==null||
                row.spotQuoteAt()==null||row.futuresQuoteAt()==null||
                row.spotQuoteFirstObservedAt()==null||
                row.futuresQuoteFirstObservedAt()==null||
                row.spotFinalFirstAvailableAt()==null||
                row.futuresFinalFirstAvailableAt()==null||
                row.cashSessionCloseAt()==null||row.futuresSessionCloseAt()==null||
                row.spotQuoteFirstObservedAt().isAfter(asOf)||
                row.futuresQuoteFirstObservedAt().isAfter(asOf)||
                row.spotFinalFirstAvailableAt().isAfter(asOf)||
                row.futuresFinalFirstAvailableAt().isAfter(asOf)||
                !FnoExpiryBasisReversionV1.cashClose(row.sessionDate())
                    .equals(row.cashSessionCloseAt())||
                !FnoExpiryBasisReversionV1.futuresClose(row.sessionDate())
                    .equals(row.futuresSessionCloseAt())||
                !calendar.isTradingDay(row.sessionDate())||
                !digest(row.contractSourceDigest())||
                !digest(row.carryCostSourceDigest())||
                (previous!=null&&(!row.sessionDate().isAfter(previous.sessionDate())||
                    !row.provider().equals(previous.provider())||
                    !row.listedFrom().equals(previous.listedFrom())||
                    !row.contractSourceDigest().equals(previous.contractSourceDigest())||
                    !row.carryCostSourceDigest().equals(previous.carryCostSourceDigest())||
                    !row.assumptions().equals(previous.assumptions()))))
                return blocked("STR180_ORIGINAL_PAIR_CUSTODY_OR_IDENTITY_INVALID",rows.size());
            previous=row;
        }
        var latest=rows.subList(rows.size()-5,rows.size());
        var pairs=new ArrayList<FnoExpiryBasisReversionV1.CompletedPair>();
        LocalDate prior=null;
        for(var row:latest) {
            if(prior!=null)
                for(var day=prior.plusDays(1);day.isBefore(row.sessionDate());
                    day=day.plusDays(1))
                    if(calendar.isTradingDay(day))
                        return blocked("STR180_HISTORICAL_EXCHANGE_SESSION_GAP",rows.size());
            pairs.add(new FnoExpiryBasisReversionV1.CompletedPair(
                row.sessionDate(),true,spotKey,futureKey,row.provider(),
                new FnoExpiryBasisReversionV1.Quote(
                    row.spotBid(),row.spotAsk(),row.spotQuoteAt(),
                    row.spotQuoteFirstObservedAt()),
                new FnoExpiryBasisReversionV1.Quote(
                    row.futuresBid(),row.futuresAsk(),row.futuresQuoteAt(),
                    row.futuresQuoteFirstObservedAt()),
                true,true,row.spotFinalFirstAvailableAt(),
                row.futuresFinalFirstAvailableAt()));
            prior=row.sessionDate();
        }
        var last=latest.getLast();
        var contract=new FnoExpiryBasisReversionV1.Contract(
            spotKey,futureKey,spotKey,last.listedFrom(),expiry,lotSize,
            true,true);
        final FnoExpiryBasisReversionV1.Decision result;
        try {
            result=FnoExpiryBasisReversionV1.evaluate(
                contract,List.copyOf(pairs),
                new FnoExpiryBasisReversionV1.SourceRights(true,true,true),
                last.assumptions(),asOf);
        } catch(RuntimeException malformed) {
            return blocked("STR180_EVALUATOR_FAILED_CLOSED",rows.size());
        }
        if(result==null||result.status()==null||result.reasonCode()==null)
            return blocked("STR180_EVALUATOR_RESULT_INVALID",rows.size());
        boolean valid=result.status()==FnoExpiryBasisReversionV1.Status.NO_SIGNAL||
            result.status()==FnoExpiryBasisReversionV1.Status.RESEARCH_CANDIDATE;
        return new Result(STRATEGY,valid
            ?"ORIGINAL_PAIR_EVALUATOR_RESEARCH_ONLY"
            :"ORIGINAL_PAIR_EVALUATOR_BLOCKED",
            result.reasonCode(),rows.size(),valid?5:0,false,false,false,false);
    }
    private static boolean digest(String value) {
        return value!=null&&value.matches("[0-9a-f]{64}");
    }
    private static Result blocked(String reason,int observed) {
        return new Result(STRATEGY,"BLOCKED_NOT_HISTORICAL_E2E",
            reason,observed,0,false,false,false,false);
    }
}
