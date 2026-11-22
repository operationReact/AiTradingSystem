package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedExpiryBasisPairStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Original V105 cash-equity and futures near-cash-close paired quotes,
 * source-reviewed carry/dividend/delivery costs and session/custody receipts
 * -> original STR-180 five-session expiry basis-reversion Java evaluator.
 *
 * Source is independently joined to EQ/FO calendars and 3 rights receipts.
 * No LTP, OHLCV, synthetic settlement, broker order, or source mutation.
 * V105 source is initially EMPTY; fictional CI fixtures are not market E2E.
 */
@Service
public class Fno180VerifiedExpiryBasisReplayService {
    public static final String STRATEGY=FnoExpiryBasisReversionV1.STRATEGY_ID;
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final int REQUIRED=5;
    private static final int LOOKBACK=18;
    private final FnoVerifiedExpiryBasisPairStore source;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean research,expiryBasis,discovery;

    @Autowired
    public Fno180VerifiedExpiryBasisReplayService(
            FnoVerifiedExpiryBasisPairStore source,MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.research-rights-verified:false}")
            boolean research,
            @Value("${minttick.fno-research.expiry-basis-research-rights-verified:false}")
            boolean expiryBasis,
            @Value("${minttick.fno-research.contract-discovery-verified:false}")
            boolean discovery) {
        this(source,calendar,Clock.systemUTC(),research,expiryBasis,discovery);
    }
    Fno180VerifiedExpiryBasisReplayService(
            FnoVerifiedExpiryBasisPairStore source, MarketSessionCalendar calendar,
            Clock clock,boolean research,boolean expiryBasis,boolean discovery) {
        this.source=Objects.requireNonNull(source);
        this.calendar=Objects.requireNonNull(calendar);
        this.clock=Objects.requireNonNull(clock);
        this.research=research;
        this.expiryBasis=expiryBasis;
        this.discovery=discovery;
    }

    public record Result(String strategyId,String state,String reasonCode,
                         String evaluatorStatus,int originalPairedSessions,
                         int completedSessions,boolean historicalMarketE2E,
                         boolean paperLedgerE2E,boolean liveBrokerE2E,
                         boolean ordersPlaced) {}

    public Result inspect(String spotKey,String futuresKey,LocalDate expiry,
                          int lotSize,Instant asOf) {
        if(!research||!expiryBasis||!discovery)
            return blocked("STR180_EQUITY_FUTURES_CARRY_RIGHTS_UNVERIFIED",0);
        if(spotKey==null||!spotKey.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")
                || futuresKey==null
                || !futuresKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || expiry==null||lotSize<1||lotSize>100000||asOf==null
                || asOf.isAfter(clock.instant()))
            return blocked("STR180_HISTORICAL_PAIR_QUERY_SCOPE_INVALID",0);

        var today=asOf.atZone(IST).toLocalDate();
        long days=java.time.temporal.ChronoUnit.DAYS.between(today,expiry);
        if(days<3||days>14)
            return blocked("STR180_EXPIRY_BASIS_DELIVERY_WINDOW_INVALID",0);
        var to=today.minusDays(1);
        var from=to.minusDays(LOOKBACK);
        final List<FnoVerifiedExpiryBasisPairStore.PairProof> sourceRows;
        try {
            sourceRows=source.readExactPair(
                spotKey,futuresKey,expiry,lotSize,from,to,asOf);
        }catch(RuntimeException denied){
            // Do not expose SQL, actual quotes or account information.
            return blocked("STR180_ORIGINAL_EQ_FO_PAIR_SOURCE_UNAVAILABLE",0);
        }
        if(sourceRows==null||sourceRows.size()<REQUIRED)
            return blocked("STR180_FIVE_ORIGINAL_EQ_FO_PAIRS_MISSING",
                sourceRows==null?0:sourceRows.size());
        if(sourceRows.size()>LOOKBACK+1)
            return blocked("STR180_UNBOUNDED_OR_AMBIGUOUS_PAIR_SOURCE",0);

        LocalDate previous=null, listed=null;
        String provider=null,contractDigest=null,costDigest=null;
        FnoExpiryBasisReversionV1.Assumptions originalAssumptions=null;
        boolean deliverable=false;
        for(var r:sourceRows){
            if(r==null || !spotKey.equals(r.spotKey())
                    || !futuresKey.equals(r.futuresKey())
                    || !expiry.equals(r.expiry())||r.lotSize()!=lotSize
                    || r.listedFrom()==null||r.sessionDate()==null
                    || r.sessionDate().isBefore(r.listedFrom())
                    || !r.sessionDate().isBefore(expiry)
                    || !r.sessionDate().isBefore(today)
                    || r.provider()==null||!List.of(
                        "UPSTOX_ANALYTICS","ANGEL_ONE_SMARTAPI").contains(r.provider())
                    || !r.deliverableSpotVerified()
                    || !digest(r.contractSourceDigest())
                    || !digest(r.carryCostSourceDigest())
                    || r.assumptions()==null
                    || r.cashSessionCloseAt()==null||r.futuresSessionCloseAt()==null
                    || !r.cashSessionCloseAt().equals(cashClose(r.sessionDate()))
                    || !r.futuresSessionCloseAt().equals(futureClose(r.sessionDate()))
                    || r.spotQuoteAt()==null||r.futuresQuoteAt()==null
                    || r.spotQuoteFirstObservedAt()==null
                    || r.futuresQuoteFirstObservedAt()==null
                    || r.spotFinalFirstAvailableAt()==null
                    || r.futuresFinalFirstAvailableAt()==null
                    || r.spotBid()==null||r.spotAsk()==null
                    || r.futuresBid()==null||r.futuresAsk()==null
                    || r.spotBid().signum()<=0||r.futuresBid().signum()<=0
                    || r.spotAsk().compareTo(r.spotBid())<0
                    || r.futuresAsk().compareTo(r.futuresBid())<0
                    || !originalNearCashClose(r.spotQuoteAt(),r.sessionDate())
                    || !originalNearCashClose(r.futuresQuoteAt(),r.sessionDate())
                    || Duration.between(r.spotQuoteAt(),
                        r.futuresQuoteAt()).abs().toSeconds()>120
                    || r.spotQuoteAt().isAfter(r.spotQuoteFirstObservedAt())
                    || r.futuresQuoteAt().isAfter(r.futuresQuoteFirstObservedAt())
                    || r.spotQuoteFirstObservedAt().isAfter(asOf)
                    || r.futuresQuoteFirstObservedAt().isAfter(asOf)
                    || r.spotFinalFirstAvailableAt().isBefore(r.cashSessionCloseAt())
                    || r.futuresFinalFirstAvailableAt().isBefore(r.futuresSessionCloseAt())
                    || r.spotFinalFirstAvailableAt().isAfter(asOf)
                    || r.futuresFinalFirstAvailableAt().isAfter(asOf)
                    || (previous!=null&&!r.sessionDate().isAfter(previous))
                    || (provider!=null&&!provider.equals(r.provider()))
                    || (listed!=null&&!listed.equals(r.listedFrom()))
                    || (contractDigest!=null&&!contractDigest.equals(r.contractSourceDigest()))
                    || (costDigest!=null&&!costDigest.equals(r.carryCostSourceDigest()))
                    || (originalAssumptions!=null
                        && !originalAssumptions.equals(r.assumptions()))
                    || !calendar.isTradingDay(r.sessionDate()))
                return blocked("STR180_PAIRED_ORIGINAL_SOURCE_CUSTODY_INVALID",
                    sourceRows.size());
            previous=r.sessionDate();
            listed=r.listedFrom();
            provider=r.provider();
            contractDigest=r.contractSourceDigest();
            costDigest=r.carryCostSourceDigest();
            originalAssumptions=r.assumptions();
            deliverable=r.deliverableSpotVerified();
        }

        var lastFive=sourceRows.subList(sourceRows.size()-REQUIRED,sourceRows.size());
        previous=null;
        var history=new ArrayList<FnoExpiryBasisReversionV1.CompletedPair>(REQUIRED);
        for(var r:lastFive){
            if(previous!=null)
                for(var day=previous.plusDays(1);
                        day.isBefore(r.sessionDate());day=day.plusDays(1))
                    if(calendar.isTradingDay(day))
                        return blocked("STR180_VERIFIED_CASH_FUTURES_SESSION_GAP",
                            sourceRows.size());
            var spot=new FnoExpiryBasisReversionV1.Quote(
                r.spotBid(),r.spotAsk(),r.spotQuoteAt(),
                r.spotQuoteFirstObservedAt());
            var future=new FnoExpiryBasisReversionV1.Quote(
                r.futuresBid(),r.futuresAsk(),r.futuresQuoteAt(),
                r.futuresQuoteFirstObservedAt());
            history.add(new FnoExpiryBasisReversionV1.CompletedPair(
                r.sessionDate(),true,spotKey,futuresKey,r.provider(),
                spot,future,true,true,
                r.spotFinalFirstAvailableAt(),r.futuresFinalFirstAvailableAt()));
            previous=r.sessionDate();
        }
        var contract=new FnoExpiryBasisReversionV1.Contract(
            spotKey,futuresKey,spotKey,listed,expiry,lotSize,true,deliverable);
        final FnoExpiryBasisReversionV1.Decision decision;
        try{
            decision=FnoExpiryBasisReversionV1.evaluate(
                contract,List.copyOf(history),
                new FnoExpiryBasisReversionV1.SourceRights(true,true,true),
                originalAssumptions,asOf);
        }catch(RuntimeException invalid){
            return blocked("STR180_ORIGINAL_PAIR_EVALUATOR_FAILED_CLOSED",
                sourceRows.size());
        }
        if(decision==null||decision.status()==null||decision.reasonCode()==null)
            return blocked("STR180_ORIGINAL_PAIR_EVALUATOR_RESULT_INVALID",
                sourceRows.size());
        return new Result(STRATEGY,
            decision.status().name().startsWith("BLOCKED")
                ?"ORIGINAL_PAIR_EVALUATOR_BLOCKED"
                :"ORIGINAL_PAIR_EVALUATOR_RESEARCH_ONLY",
            decision.reasonCode(),decision.status().name(),sourceRows.size(),
            REQUIRED,false,false,false,false);
    }
    private static boolean digest(String s){
        return s!=null&&s.matches("[0-9a-f]{64}");
    }
    private static boolean originalNearCashClose(Instant event,LocalDate day){
        var close=cashClose(day);
        return !event.isBefore(close.minus(Duration.ofMinutes(5)))
            && !event.isAfter(close);
    }
    private static Instant cashClose(LocalDate day){
        return day.atTime(LocalTime.of(15,30)).atZone(IST).toInstant();
    }
    private static Instant futureClose(LocalDate day){
        LocalTime close=day.isBefore(LocalDate.of(2026,8,3))
            ?LocalTime.of(15,30):day.isBefore(LocalDate.of(2026,12,7))
            ?LocalTime.of(15,40):LocalTime.of(15,45);
        return day.atTime(close).atZone(IST).toInstant();
    }
    private static Result blocked(String reason,int count){
        return new Result(STRATEGY,"BLOCKED_NOT_HISTORICAL_E2E",
            reason,"NOT_RUN",count,0,false,false,false,false);
    }
}
