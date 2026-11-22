package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedIntradayLiquidityStore;
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
 * V104 original intraday futures L1/depth, finalized OI and cumulative
 * volume source -> actual STR-179 Java liquidity research evaluator.
 * The source SQL separately verifies independent MODEL_INFERENCE
 * quote/OI/volume rights receipts, original captures and first-known times.
 * This service does not infer L1/depth from OHLCV or the live quote cache.
 * It is a read-only source adapter, not a historical market E2E proof.
 */
@Service
public class Fno179VerifiedLiquidityReplayService {
    public static final String STRATEGY=FnoContractLiquidityGateV1.STRATEGY_ID;
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final int REQUIRED=3;
    private static final FnoContractLiquidityGateV1.Thresholds RESEARCH_THRESHOLDS =
        new FnoContractLiquidityGateV1.Thresholds(100,20,2,20,50,5,10,30,500);
    private final FnoVerifiedIntradayLiquidityStore source;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean research, liquidity, discovery;

    @Autowired
    public Fno179VerifiedLiquidityReplayService(
            FnoVerifiedIntradayLiquidityStore source,
            MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.research-rights-verified:false}")
            boolean research,
            @Value("${minttick.fno-research.liquidity-research-rights-verified:false}")
            boolean liquidity,
            @Value("${minttick.fno-research.contract-discovery-verified:false}")
            boolean discovery) {
        this(source,calendar,Clock.systemUTC(),research,liquidity,discovery);
    }
    Fno179VerifiedLiquidityReplayService(
            FnoVerifiedIntradayLiquidityStore source,
            MarketSessionCalendar calendar,Clock clock,
            boolean research,boolean liquidity,boolean discovery) {
        this.source=Objects.requireNonNull(source);
        this.calendar=Objects.requireNonNull(calendar);
        this.clock=Objects.requireNonNull(clock);
        this.research=research;
        this.liquidity=liquidity;
        this.discovery=discovery;
    }

    public record Evidence(String strategyId,String state,String reasonCode,
                           int originalObservations,int evaluatedObservations,
                           String evaluatorStatus,boolean historicalMarketE2E,
                           boolean paperLedgerE2E,boolean liveBrokerE2E,
                           boolean ordersPlaced) {}

    /**
     * Bounded original V104 source query; only FUTURE identity is supported.
     * Options additionally require official option strike/type listing and
     * cannot be reconstructed from the V104 futures-only table.
     */
    public Evidence inspect(String key,LocalDate expiry,int lotSize,Instant asOf){
        if(!research || !liquidity || !discovery)
            return blocked("STR179_RESEARCH_QUOTE_OI_VOLUME_OR_DISCOVERY_RIGHTS_MISSING",0);
        if(key==null || !key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || expiry==null || lotSize<1 || lotSize>100000
                || asOf==null || asOf.isAfter(clock.instant())
                || expiry.isBefore(asOf.atZone(IST).toLocalDate()))
            return blocked("STR179_HISTORICAL_L1_QUERY_SCOPE_INVALID",0);
        var date=asOf.atZone(IST).toLocalDate();
        var time=asOf.atZone(IST).toLocalTime();
        if(!calendar.isTradingDay(date)
                || time.isBefore(LocalTime.of(9,15))
                || time.isAfter(derivativesClose(date)))
            return blocked("STR179_NSE_FO_MARKET_SESSION_NOT_VERIFIED",0);

        final List<FnoVerifiedIntradayLiquidityStore.Snapshot> rows;
        try {
            rows=source.latestThreeFutures(key,expiry,lotSize,asOf);
        } catch(RuntimeException inaccessible) {
            return blocked("STR179_ORIGINAL_INTRADAY_L1_SOURCE_UNAVAILABLE",0);
        }
        if(rows==null || rows.size()!=REQUIRED)
            return blocked("STR179_THREE_ORIGINAL_L1_OBSERVATIONS_MISSING",
                rows==null?0:rows.size());
        String provider=null,underlying=null;
        Instant previous=null;
        for(var b:rows){
            if(b==null || !key.equals(b.instrumentKey())
                    || !expiry.equals(b.expiry()) || b.lotSize()!=lotSize
                    || !"FUTURE".equals(b.kind())
                    || b.provider()==null || !"UPSTOX_V3".equals(b.provider())
                    || b.underlyingKey()==null
                    || !b.underlyingKey().matches(
                        "(?:NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})")
                    || b.exchangeAt()==null || b.observedAt()==null
                    || b.oiFirstObservedAt()==null
                    || b.volumeFirstObservedAt()==null
                    || !date.equals(b.exchangeAt().atZone(IST).toLocalDate())
                    || !date.equals(b.observedAt().atZone(IST).toLocalDate())
                    || b.exchangeAt().atZone(IST).toLocalTime().isBefore(LocalTime.of(9,15))
                    || b.exchangeAt().atZone(IST).toLocalTime().isAfter(derivativesClose(date))
                    || b.exchangeAt().isAfter(b.observedAt())
                    || b.observedAt().isAfter(asOf)
                    || b.oiFirstObservedAt().isAfter(asOf)
                    || b.volumeFirstObservedAt().isAfter(asOf)
                    || b.volumeFirstObservedAt().isAfter(b.observedAt())
                    || b.oiFirstObservedAt().isAfter(b.observedAt())
                    || b.bid()==null || b.ask()==null
                    || b.bid().signum()<=0 || b.ask().compareTo(b.bid())<0
                    || b.bidDepthUnits()<0 || b.askDepthUnits()<0
                    || b.oiUnits()<=0 || b.cumulativeVolumeUnits()<0
                    || (previous!=null && !b.exchangeAt().isAfter(previous))
                    || (provider!=null && !provider.equals(b.provider()))
                    || (underlying!=null && !underlying.equals(b.underlyingKey())))
                return blocked("STR179_ORIGINAL_L1_PROVENANCE_OR_CONTRACT_AMBIGUOUS",
                    rows.size());
            previous=b.exchangeAt();
            provider=b.provider();
            underlying=b.underlyingKey();
        }
        var contract=new FnoContractLiquidityGateV1.Contract(
            key,underlying,expiry,lotSize,
            FnoContractLiquidityGateV1.DerivativeKind.FUTURE,null,true);
        var snapshots=new ArrayList<FnoContractLiquidityGateV1.MarketSnapshot>(REQUIRED);
        for(var b:rows){
            snapshots.add(new FnoContractLiquidityGateV1.MarketSnapshot(
                key,b.provider(),b.exchangeAt(),b.observedAt(),
                b.bid(),b.ask(),b.bidDepthUnits(),b.askDepthUnits(),
                b.oiUnits(),b.cumulativeVolumeUnits(),b.oiFirstObservedAt(),
                b.volumeFirstObservedAt(),true,true,true,true));
        }
        final FnoContractLiquidityGateV1.Decision decision;
        try{
            decision=FnoContractLiquidityGateV1.evaluate(
                contract,List.copyOf(snapshots),
                new FnoContractLiquidityGateV1.SourceRights(true,true,true,true),
                RESEARCH_THRESHOLDS,asOf);
        }catch(RuntimeException malformed){
            return blocked("STR179_ORIGINAL_L1_EVALUATOR_FAILED_CLOSED",rows.size());
        }
        if(decision==null || decision.status()==null || decision.reasonCode()==null)
            return blocked("STR179_ORIGINAL_L1_EVALUATOR_RESULT_INVALID",rows.size());
        return new Evidence(STRATEGY,
            decision.status().name().startsWith("BLOCKED")
                ? "ORIGINAL_L1_EVALUATOR_BLOCKED"
                : "ORIGINAL_L1_EVALUATOR_RESEARCH_ONLY",
            decision.reasonCode(),rows.size(),REQUIRED,
            decision.status().name(),false,false,false,false);
    }

    private static LocalTime derivativesClose(LocalDate day){
        if(!day.isBefore(LocalDate.of(2026,12,7)))return LocalTime.of(15,45);
        if(!day.isBefore(LocalDate.of(2026,8,3)))return LocalTime.of(15,40);
        return LocalTime.of(15,30);
    }
    private static Evidence blocked(String reason,int rows) {
        return new Evidence(STRATEGY,"BLOCKED_NOT_HISTORICAL_E2E",
            reason,rows,0,"NOT_RUN",false,false,false,false);
    }
}
