package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.Fno170VerifiedIntradayPairStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * STR-170 source-original intraday paired NSE_EQ + NSE_FO quotes into the
 * original actual cash/futures basis evaluator. V105 FINAL EOD historical
 * quotes are forbidden because they cannot satisfy the 15s L1 freshness
 * and independent as-of publication checks simultaneously.
 *
 * <p>Research only, no customer quote export, broker API or orders.
 * Success does not establish execution, after-cost OOS or E2E acceptance.
 */
@Service
public class Fno170VerifiedHistoricalReplayService {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final String ID=FnoFuturesBasisCarryV1.STRATEGY_ID;
    private final Fno170VerifiedIntradayPairStore source;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean researchRights,originalIntradayRights,discoveryRights;

    @Autowired
    public Fno170VerifiedHistoricalReplayService(
            Fno170VerifiedIntradayPairStore source,MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.research-rights-verified:false}")
            boolean researchRights,
            @Value("${minttick.fno-research.intraday-basis-research-rights-verified:false}")
            boolean originalIntradayRights,
            @Value("${minttick.fno-research.contract-discovery-verified:false}")
            boolean discoveryRights) {
        this(source,calendar,Clock.systemUTC(),
            researchRights,originalIntradayRights,discoveryRights);
    }

    Fno170VerifiedHistoricalReplayService(
            Fno170VerifiedIntradayPairStore source,MarketSessionCalendar calendar,
            Clock clock,boolean researchRights,boolean originalIntradayRights,
            boolean discoveryRights) {
        this.source=Objects.requireNonNull(source);
        this.calendar=Objects.requireNonNull(calendar);
        this.clock=Objects.requireNonNull(clock);
        this.researchRights=researchRights;
        this.originalIntradayRights=originalIntradayRights;
        this.discoveryRights=discoveryRights;
    }

    public record Result(String strategyId,String state,String reasonCode,
        int originalPairedObservations,int evaluatorCalls,
        String localSourceInputSha256,String evaluatorStatus,
        boolean independentHistoricalE2E,boolean paperLedgerE2E,
        boolean liveBrokerE2E,boolean ordersPlaced) {}

    public Result inspect(String stock,String future,LocalDate expiry,
                          int lotSize,Instant asOf) {
        if(!researchRights||!originalIntradayRights||!discoveryRights)
            return blocked("STR170_SOURCE_RIGHTS_OR_LISTING_NOT_VERIFIED");
        if(stock==null||!stock.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")
                ||future==null||!future.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                ||expiry==null||lotSize<1||lotSize>100000||asOf==null
                ||asOf.isAfter(clock.instant())
                ||asOf.isBefore(clock.instant().minus(366,ChronoUnit.DAYS))
                ||expiry.isBefore(asOf.atZone(IST).toLocalDate()))
            return blocked("STR170_REPLAY_QUERY_SCOPE_INVALID");
        if(!calendar.isInsideCashSession(asOf)
                ||!calendar.isInsideFnoSession(asOf))
            return blocked("STR170_CASH_AND_FO_SESSION_NOT_VERIFIED");

        final List<Fno170VerifiedIntradayPairStore.Pair> records;
        try {
            records=source.readExactPair(stock,future,expiry,lotSize,asOf);
        } catch(RuntimeException sourceUnavailable) {
            return blocked("STR170_ORIGINAL_INTRADAY_PAIR_UNAVAILABLE");
        }
        if(records==null || records.size()!=1)
            return blocked("STR170_ONE_ORIGINAL_INTRADAY_PAIR_MISSING");
        var pair=records.getFirst();
        if(pair==null || pair.provider()==null
                ||!List.of("UPSTOX_V3","ANGEL_ONE_SMARTAPI").contains(pair.provider())
                ||!stock.equals(pair.spotKey())||!future.equals(pair.futuresKey())
                ||!expiry.equals(pair.expiry())||lotSize!=pair.lotSize()
                ||pair.listedFrom()==null
                ||pair.listedFrom().isAfter(asOf.atZone(IST).toLocalDate())
                ||!pair.deliveryVerified()||!pair.costsVerified()
                ||!hash(pair.spotDigest())||!hash(pair.futuresDigest())
                ||!hash(pair.listingDigest())
                ||!hash(pair.financingCostDigest())
                ||pair.spotExchangeAt()==null||pair.futuresExchangeAt()==null
                ||pair.spotFirstObservedAt()==null
                ||pair.futuresFirstObservedAt()==null
                ||pair.spotBid()==null||pair.spotAsk()==null
                ||pair.futuresBid()==null||pair.futuresAsk()==null
                ||pair.fundingAnnualBps()==null
                ||pair.dividendAnnualBps()==null
                ||pair.roundTripCostBps()==null||pair.minNetEdgeBps()==null
                ||!asOf.atZone(IST).toLocalDate().equals(
                    pair.spotExchangeAt().atZone(IST).toLocalDate())
                ||!asOf.atZone(IST).toLocalDate().equals(
                    pair.futuresExchangeAt().atZone(IST).toLocalDate())
                ||pair.spotExchangeAt().isAfter(pair.spotFirstObservedAt())
                ||pair.futuresExchangeAt().isAfter(pair.futuresFirstObservedAt())
                ||pair.spotFirstObservedAt().isAfter(asOf)
                ||pair.futuresFirstObservedAt().isAfter(asOf)
                ||Duration.between(pair.spotExchangeAt(),asOf).isNegative()
                ||Duration.between(pair.futuresExchangeAt(),asOf).isNegative()
                ||Duration.between(pair.spotExchangeAt(),asOf).toSeconds()>15
                ||Duration.between(pair.futuresExchangeAt(),asOf).toSeconds()>15
                ||Duration.between(pair.spotExchangeAt(),
                    pair.futuresExchangeAt()).abs().toSeconds()>10)
            return blocked("STR170_ORIGINAL_INSTRUMENT_QUOTE_OR_COST_PROVENANCE_INVALID");

        var identity=new FnoFuturesBasisCarryV1.Contract(
            stock,future,stock,expiry,lotSize,true,true);
        var spotQuote=new FnoFuturesBasisCarryV1.Quote(
            pair.spotBid(),pair.spotAsk(),pair.spotExchangeAt(),
            pair.spotFirstObservedAt());
        var futuresQuote=new FnoFuturesBasisCarryV1.Quote(
            pair.futuresBid(),pair.futuresAsk(),pair.futuresExchangeAt(),
            pair.futuresFirstObservedAt());
        var rights=new FnoFuturesBasisCarryV1.SourceRights(
            true,true,true,false /* independent stock borrow NEVER inferred */);
        var researchCosts=new FnoFuturesBasisCarryV1.Assumptions(
            pair.fundingAnnualBps(),pair.dividendAnnualBps(),
            java.math.BigDecimal.ZERO /* unused since borrow rights FALSE */,
            pair.roundTripCostBps(),pair.minNetEdgeBps());
        final FnoFuturesBasisCarryV1.Decision decision;
        try {
            decision=FnoFuturesBasisCarryV1.evaluate(
                identity,spotQuote,futuresQuote,rights,researchCosts,asOf);
        } catch(RuntimeException badInputs) {
            return blocked("STR170_SOURCE_EVALUATOR_FAILED_CLOSED");
        }
        if(decision==null||decision.status()==null)
            return blocked("STR170_EVALUATOR_RESULT_MISSING");
        String state=decision.status().name().startsWith("BLOCKED")
            ? "ORIGINAL_INTRADAY_EVALUATOR_BLOCKED"
            : "ORIGINAL_INTRADAY_RESEARCH_ONLY";
        return new Result(ID,state,decision.reasonCode(),1,1,
            digest(pair),decision.status().name(),
            false,false,false,false);
    }

    private static boolean hash(String h) {
        return h!=null&&h.matches("[a-f0-9]{64}");
    }
    private static String digest(Fno170VerifiedIntradayPairStore.Pair p){
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            String source=p.spotExchangeAt()+"|"+p.futuresExchangeAt()+"|"+
                p.spotDigest()+"|"+p.futuresDigest()+"|"+p.listingDigest()+
                "|"+p.financingCostDigest();
            return HexFormat.of().formatHex(
                hash.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_MISSING",impossible);
        }
    }
    private static Result blocked(String reason) {
        return new Result(ID,"BLOCKED_NOT_HISTORICAL_E2E",reason,
            0,0,null,"NOT_RUN",false,false,false,false);
    }
}
