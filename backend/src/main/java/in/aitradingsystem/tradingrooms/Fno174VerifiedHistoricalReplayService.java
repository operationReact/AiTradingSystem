package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedFinalPriceOiSessionStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Clock;
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
 * Source-rights-joined original final futures CLOSE plus independent final OI
 * historical sessions -> existing STR-174 Java evaluator. Reads V100 via
 * original readExactContract: no LTP, broker quote, session-midnight candle,
 * synthetic price, public route, provider request or order execution.
 */
@Service
public final class Fno174VerifiedHistoricalReplayService {
    private static final String STRATEGY=FnoFuturesPriceOiDivergenceV1.STRATEGY_ID;
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final int MIN=5;
    private static final int LOOKBACK=20;
    private static final FnoFuturesPriceOiDivergenceV1.Thresholds RESEARCH_THRESHOLDS =
        new FnoFuturesPriceOiDivergenceV1.Thresholds(
            500,500,3,2500,2500,5000);
    private final FnoVerifiedFinalPriceOiSessionStore source;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean researchRights, finalizedPriceRights, discoveryRights;

    @Autowired
    public Fno174VerifiedHistoricalReplayService(
            FnoVerifiedFinalPriceOiSessionStore source,
            MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.research-rights-verified:false}")
            boolean researchRights,
            @Value("${minttick.fno-research.finalized-price-research-rights-verified:false}")
            boolean finalizedPriceRights,
            @Value("${minttick.fno-research.contract-discovery-verified:false}")
            boolean discoveryRights) {
        this(source,calendar,Clock.systemUTC(),researchRights,
            finalizedPriceRights,discoveryRights);
    }
    Fno174VerifiedHistoricalReplayService(
            FnoVerifiedFinalPriceOiSessionStore source,
            MarketSessionCalendar calendar, Clock clock,
            boolean researchRights, boolean finalizedPriceRights,
            boolean discoveryRights) {
        this.source=Objects.requireNonNull(source);
        this.calendar=Objects.requireNonNull(calendar);
        this.clock=Objects.requireNonNull(clock);
        this.researchRights=researchRights;
        this.finalizedPriceRights=finalizedPriceRights;
        this.discoveryRights=discoveryRights;
    }

    public record Result(String strategyId, String state, String reason,
                         String evaluatorStatus, int originalSourceRows,
                         int completedSessions, boolean historicalE2EAccepted,
                         boolean paperLedgerE2E, boolean liveBrokerE2E,
                         boolean ordersPlaced) {}

    public Result inspect(String instrumentKey, Instant asOf) {
        if (!researchRights || !finalizedPriceRights || !discoveryRights)
            return deny("STR174_FINAL_PRICE_OR_DISCOVERY_RIGHTS_NOT_VERIFIED",0);
        if (instrumentKey == null
                || !instrumentKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || asOf == null || asOf.isAfter(clock.instant()))
            return deny("STR174_HISTORICAL_DECISION_SCOPE_INVALID",0);
        var end=asOf.atZone(IST).toLocalDate().minusDays(1);
        var start=end.minusDays(LOOKBACK);
        final List<FnoVerifiedFinalPriceOiSessionStore.FinalSession> sourceRows;
        try {
            sourceRows=source.readExactContract(instrumentKey,start,end,asOf);
        } catch(RuntimeException inaccessible) {
            return deny("STR174_ORIGINAL_FINAL_PRICE_OI_SOURCE_UNAVAILABLE",0);
        }
        if (sourceRows == null || sourceRows.size() < MIN)
            return deny("STR174_FIVE_ORIGINAL_FINAL_SESSIONS_MISSING",
                sourceRows == null?0:sourceRows.size());
        if (sourceRows.size() > LOOKBACK+1)
            return deny("STR174_ORIGINAL_FINAL_SOURCE_AMBIGUOUS",0);
        String provider=null;
        String underlying=null;
        LocalDate expiry=null,prev=null;
        int lot=0;
        for(var bar:sourceRows) {
            if (bar == null || !instrumentKey.equals(bar.instrumentKey())
                    || bar.providerLabel() == null || !List.of(
                        "UPSTOX_ANALYTICS","ANGEL_ONE_SMARTAPI").contains(bar.providerLabel())
                    || bar.underlyingKey() == null
                    || bar.expiry() == null || bar.lotSize() < 1
                    || bar.lotSize() > 100000 || bar.sessionDate() == null
                    || !bar.sessionDate().isBefore(bar.expiry())
                    || !bar.sessionDate().isBefore(asOf.atZone(IST).toLocalDate())
                    || bar.finalClosePrice() == null
                    || bar.finalClosePrice().signum() <= 0
                    || bar.finalOpenInterestUnits() <= 0
                    || bar.tradedVolumeUnits() < 0
                    || bar.finalPriceFirstAvailableAt() == null
                    || bar.finalizedOiFirstAvailableAt() == null
                    || bar.verifiedExchangeCloseAt() == null
                    || !bar.verifiedExchangeCloseAt().equals(
                        officialNseFuturesClose(bar.sessionDate()))
                    || bar.finalPriceFirstAvailableAt().isBefore(bar.verifiedExchangeCloseAt())
                    || bar.finalizedOiFirstAvailableAt().isBefore(bar.verifiedExchangeCloseAt())
                    || bar.finalPriceFirstAvailableAt().isAfter(asOf)
                    || bar.finalizedOiFirstAvailableAt().isAfter(asOf)
                    || (prev != null && !bar.sessionDate().isAfter(prev))
                    || (provider != null && !provider.equals(bar.providerLabel()))
                    || (underlying != null && !underlying.equals(bar.underlyingKey()))
                    || (expiry != null && !expiry.equals(bar.expiry()))
                    || (lot != 0 && lot != bar.lotSize())
                    || !calendar.isTradingDay(bar.sessionDate()))
                return deny("STR174_ORIGINAL_FINAL_SOURCE_IDENTITY_OR_TIME_INVALID",
                    sourceRows.size());
            provider=bar.providerLabel();
            underlying=bar.underlyingKey();
            expiry=bar.expiry();
            lot=bar.lotSize();
            prev=bar.sessionDate();
        }

        var five=sourceRows.subList(sourceRows.size()-MIN,sourceRows.size());
        prev=null;
        var history=new ArrayList<FnoFuturesPriceOiDivergenceV1.CompletedSession>();
        for(var bar:five) {
            if (prev != null) for(var day=prev.plusDays(1);
                     day.isBefore(bar.sessionDate());day=day.plusDays(1))
                if (calendar.isTradingDay(day))
                    return deny("STR174_ORIGINAL_TRADING_SESSION_GAP",sourceRows.size());
            history.add(new FnoFuturesPriceOiDivergenceV1.CompletedSession(
                bar.sessionDate(),true,bar.instrumentKey(),bar.providerLabel(),
                bar.finalClosePrice(),true,bar.finalPriceFirstAvailableAt(),
                bar.finalOpenInterestUnits(),bar.tradedVolumeUnits(),
                bar.finalizedOiFirstAvailableAt()));
            prev=bar.sessionDate();
        }

        var contract=new FnoFuturesPriceOiDivergenceV1.Contract(
            instrumentKey,underlying,expiry,lot,true);
        final FnoFuturesPriceOiDivergenceV1.Decision decision;
        try {
            decision=FnoFuturesPriceOiDivergenceV1.evaluate(
                contract,List.copyOf(history),
                new FnoFuturesPriceOiDivergenceV1.SourceRights(true,true,true),
                RESEARCH_THRESHOLDS,asOf);
        } catch(RuntimeException malformed){
            return deny("STR174_ORIGINAL_EVALUATOR_FAILED_CLOSED",
                sourceRows.size());
        }
        if(decision==null || decision.status()==null || decision.reasonCode()==null)
            return deny("STR174_ORIGINAL_EVALUATOR_RESULT_INVALID",sourceRows.size());
        return new Result(STRATEGY,
            decision.status().name().startsWith("BLOCKED")
                ?"ORIGINAL_FINAL_SOURCE_EVALUATOR_BLOCKED"
                :"ORIGINAL_FINAL_SOURCE_RESEARCH_ONLY",
            decision.reasonCode(),decision.status().name(),sourceRows.size(),
            five.size(),false,false,false,false);
    }

    private static Instant officialNseFuturesClose(LocalDate date) {
        LocalTime end=date.isBefore(LocalDate.of(2026,8,3))
            ?LocalTime.of(15,30)
            :date.isBefore(LocalDate.of(2026,12,7))
            ?LocalTime.of(15,40):LocalTime.of(15,45);
        return date.atTime(end).atZone(IST).toInstant();
    }

    private static Result deny(String reason,int rows){
        return new Result(STRATEGY,"BLOCKED_NOT_HISTORICAL_E2E",
            reason,"NOT_RUN",rows,0,false,false,false,false);
    }
}
