package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedIntradayLiquidityStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * STR-179 original source-rights-joined V103 intraday L1/OI/volume evidence
 * into the actual liquidity evaluator at the historical decision as-of.
 * Unlike futures daily OHLCV, a liquidity screen needs three genuine original
 * exchange-timed bid/ask/depth snapshots, not a proxy reconstructed from bars.
 * Research-only, no order, no source entitlement changes or data export.
 */
@Service
public class Fno179VerifiedHistoricalReplayService {
    public static final String STRATEGY = FnoContractLiquidityGateV1.STRATEGY_ID;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration MAX_HISTORY = Duration.ofDays(30);
    private static final FnoContractLiquidityGateV1.Thresholds THRESHOLDS =
        new FnoContractLiquidityGateV1.Thresholds(100,20,2,20,50,5,10,30,500);

    private final FnoVerifiedIntradayLiquidityStore source;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean research, liquidityResearch, discovery;

    @Autowired
    public Fno179VerifiedHistoricalReplayService(
        FnoVerifiedIntradayLiquidityStore source, MarketSessionCalendar calendar,
        @Value("${minttick.fno-research.research-rights-verified:false}") boolean research,
        @Value("${minttick.fno-research.liquidity-research-rights-verified:false}") boolean liquidityResearch,
        @Value("${minttick.fno-research.contract-discovery-verified:false}") boolean discovery) {
        this(source,calendar,Clock.systemUTC(),research,liquidityResearch,discovery);
    }

    Fno179VerifiedHistoricalReplayService(
        FnoVerifiedIntradayLiquidityStore source, MarketSessionCalendar calendar,
        Clock clock,boolean research,boolean liquidityResearch,boolean discovery) {
        this.source=Objects.requireNonNull(source);
        this.calendar=Objects.requireNonNull(calendar);
        this.clock=Objects.requireNonNull(clock);
        this.research=research;
        this.liquidityResearch=liquidityResearch;
        this.discovery=discovery;
    }

    public record Result(String strategyId,String state,String reason,
        int originalSourceSnapshots,int evaluatorSnapshots,
        boolean independentHistoricalE2E,boolean paperLedgerE2E,
        boolean liveBrokerE2E,boolean brokerOrdersPlaced) {}

    public Result inspect(String instrumentKey,LocalDate expiry,int lot,Instant asOf) {
        if(!research||!liquidityResearch||!discovery)
            return blocked("STR179_ORIGINAL_QUOTE_OI_VOLUME_RIGHTS_MISSING",0);
        if(instrumentKey==null||
                !instrumentKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")||
                expiry==null||lot<1||lot>100000||asOf==null||
                asOf.isAfter(clock.instant())||
                asOf.isBefore(clock.instant().minus(MAX_HISTORY))||
                expiry.isBefore(asOf.atZone(IST).toLocalDate()))
            return blocked("STR179_HISTORICAL_SCOPE_INVALID",0);
        if(!calendar.isInsideFnoSession(asOf))
            return blocked("STR179_EXCHANGE_SESSION_NOT_VERIFIED",0);

        final List<FnoVerifiedIntradayLiquidityStore.Snapshot> rows;
        try {
            // Existing SQL enforces three distinct independently VERIFIED
            // MODEL_INFERENCE quote, OI and volume source rights, captured_at
            // <= historical asOf and 90-second bounded original quote times.
            rows=source.latestThreeFutures(instrumentKey,expiry,lot,asOf);
        } catch(RuntimeException unavailable) {
            return blocked("STR179_ORIGINAL_SOURCE_UNAVAILABLE",0);
        }
        if(rows==null||rows.size()!=3)
            return blocked("STR179_THREE_ORIGINAL_SNAPSHOTS_MISSING",
                rows==null?0:rows.size());

        var inputs=new ArrayList<FnoContractLiquidityGateV1.MarketSnapshot>(3);
        String provider=null,underlying=null;
        Instant previous=null;
        LocalDate decisionDay=asOf.atZone(IST).toLocalDate();
        for(var row:rows) {
            if(row==null||!instrumentKey.equals(row.instrumentKey())||
                    !expiry.equals(row.expiry())||lot!=row.lotSize()||
                    !"FUTURE".equals(row.kind())||
                    row.underlyingKey()==null||
                    !row.underlyingKey().matches(
                        "(?:NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})")||
                    !List.of("UPSTOX_ANALYTICS","ANGEL_ONE_SMARTAPI")
                        .contains(row.provider())||
                    (provider!=null&&!provider.equals(row.provider()))||
                    (underlying!=null&&!underlying.equals(row.underlyingKey()))||
                    row.exchangeAt()==null||row.observedAt()==null||
                    row.oiFirstObservedAt()==null||row.volumeFirstObservedAt()==null||
                    !decisionDay.equals(row.exchangeAt().atZone(IST).toLocalDate())||
                    row.exchangeAt().isAfter(asOf)||
                    row.observedAt().isAfter(asOf)||
                    row.oiFirstObservedAt().isAfter(asOf)||
                    row.volumeFirstObservedAt().isAfter(asOf)||
                    (previous!=null&&!row.exchangeAt().isAfter(previous)))
                return blocked("STR179_ORIGINAL_QUOTE_OR_IDENTITY_AMBIGUOUS",3);
            previous=row.exchangeAt();
            provider=row.provider();
            underlying=row.underlyingKey();
            inputs.add(new FnoContractLiquidityGateV1.MarketSnapshot(
                row.instrumentKey(),row.provider(),row.exchangeAt(),
                row.observedAt(),row.bid(),row.ask(),row.bidDepthUnits(),
                row.askDepthUnits(),row.oiUnits(),row.cumulativeVolumeUnits(),
                row.oiFirstObservedAt(),row.volumeFirstObservedAt(),
                true,true,true,true));
        }

        var contract=new FnoContractLiquidityGateV1.Contract(
            instrumentKey,underlying,expiry,lot,
            FnoContractLiquidityGateV1.DerivativeKind.FUTURE,null,true);
        var rights=new FnoContractLiquidityGateV1.SourceRights(
            true,true,true,true);
        final FnoContractLiquidityGateV1.Decision d;
        try {
            d=FnoContractLiquidityGateV1.evaluate(
                contract,List.copyOf(inputs),rights,THRESHOLDS,asOf);
        } catch(RuntimeException malformed) {
            return blocked("STR179_EVALUATOR_FAILED_CLOSED",3);
        }
        if(d==null||d.status()==null||d.reasonCode()==null)
            return blocked("STR179_EVALUATOR_RESULT_INVALID",3);
        return new Result(STRATEGY,
            d.status()==FnoContractLiquidityGateV1.Status.RESEARCH_PASS
                || d.status()==FnoContractLiquidityGateV1.Status.ILLIQUID
                ? "ORIGINAL_LIQUIDITY_SOURCE_EVALUATED_RESEARCH_ONLY"
                : "ORIGINAL_LIQUIDITY_EVALUATOR_BLOCKED",
            d.reasonCode(),3,d.inspectedSnapshots(),
            false,false,false,false);
    }

    private static Result blocked(String reason,int observed) {
        return new Result(STRATEGY,"BLOCKED_NOT_HISTORICAL_E2E",reason,
            observed,0,false,false,false,false);
    }
}
