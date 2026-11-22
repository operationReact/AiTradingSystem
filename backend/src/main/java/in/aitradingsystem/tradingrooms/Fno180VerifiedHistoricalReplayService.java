package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedExpiryBasisPairStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * STR-180 original-source rights-attested CASH+FUTURES paired historical
 * evaluator. V105 original near-close quotes, final-publication timestamps,
 * independent NSE cash/futures calendars and verified carry-cost assumptions
 * are mandatory. No synthetic fair-basis input, settlement substitution,
 * market-data export or broker order.
 */
@Service
public class Fno180VerifiedHistoricalReplayService {
    private static final String ID = FnoExpiryBasisReversionV1.STRATEGY_ID;
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private final FnoVerifiedExpiryBasisPairStore source;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean researchRights, basisRights, discoveryRights;

    @Autowired
    public Fno180VerifiedHistoricalReplayService(
            FnoVerifiedExpiryBasisPairStore source,MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.research-rights-verified:false}")
            boolean researchRights,
            @Value("${minttick.fno-research.expiry-basis-research-rights-verified:false}")
            boolean basisRights,
            @Value("${minttick.fno-research.contract-discovery-verified:false}")
            boolean discoveryRights) {
        this(source,calendar,Clock.systemUTC(),
            researchRights,basisRights,discoveryRights);
    }
    Fno180VerifiedHistoricalReplayService(
            FnoVerifiedExpiryBasisPairStore source,MarketSessionCalendar calendar,
            Clock clock,boolean researchRights,boolean basisRights,
            boolean discoveryRights) {
        this.source=Objects.requireNonNull(source);
        this.calendar=Objects.requireNonNull(calendar);
        this.clock=Objects.requireNonNull(clock);
        this.researchRights=researchRights;
        this.basisRights=basisRights;
        this.discoveryRights=discoveryRights;
    }

    public record Result(
        String strategyId,String state,String evaluatorReasonCode,
        int originalPairedSourceRows,int verifiedPairedSessions,
        String privateDerivedInputSha256,String evaluatorStatus,
        boolean acceptedHistoricalE2E,boolean paperLedgerE2E,
        boolean liveBrokerE2E,boolean ordersPlaced) {}

    public Result inspect(String equityKey,String futuresKey,
                          LocalDate expiry,int lotSize,Instant asOf) {
        if (!researchRights || !basisRights || !discoveryRights)
            return blocked("STR180_SOURCE_CARRY_RIGHTS_OR_DISCOVERY_UNVERIFIED",0);
        if (equityKey==null || !equityKey.matches("NSE_EQ\\|[A-Za-z0-9._-]{1,64}")
                || futuresKey==null
                || !futuresKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || expiry==null || lotSize<1 || lotSize>100000
                || asOf==null || asOf.isAfter(clock.instant())
                || asOf.isBefore(clock.instant().minus(366,ChronoUnit.DAYS)))
            return blocked("STR180_HISTORICAL_SCOPE_INVALID",0);
        LocalDate day=asOf.atZone(IST).toLocalDate();
        long remaining=ChronoUnit.DAYS.between(day,expiry);
        if (remaining<3 || remaining>14)
            return blocked("STR180_EXPIRY_OUTSIDE_RESEARCH_WINDOW",0);

        final List<FnoVerifiedExpiryBasisPairStore.PairProof> sourceRows;
        try {
            sourceRows=source.readExactPair(equityKey,futuresKey,
                expiry,lotSize,day.minusDays(21),day.minusDays(1),asOf);
        } catch (RuntimeException unavailable) {
            return blocked("STR180_ORIGINAL_SOURCE_UNAVAILABLE",0);
        }
        if (sourceRows==null || sourceRows.size()<5)
            return blocked("STR180_FIVE_ORIGINAL_SESSION_PAIRS_MISSING",
                sourceRows==null?0:sourceRows.size());
        if (sourceRows.size()>=40)
            return blocked("STR180_ORIGINAL_SOURCE_AMBIGUOUS",0);

        String provider=null,contractHash=null,carryHash=null;
        FnoExpiryBasisReversionV1.Assumptions assumptions=null;
        LocalDate listedFrom=null,previous=null;
        for (var p:sourceRows) {
            if (p==null || p.sessionDate()==null || p.listedFrom()==null
                    || p.assumptions()==null || p.provider()==null
                    || !List.of("UPSTOX_ANALYTICS","ANGEL_ONE_SMARTAPI")
                        .contains(p.provider())
                    || !equityKey.equals(p.spotKey())
                    || !futuresKey.equals(p.futuresKey())
                    || !expiry.equals(p.expiry()) || p.lotSize()!=lotSize
                    || !p.deliverableSpotVerified()
                    || p.sessionDate().isBefore(p.listedFrom())
                    || !p.sessionDate().isBefore(expiry)
                    || !p.sessionDate().isBefore(day)
                    || !validHash(p.contractSourceDigest())
                    || !validHash(p.carryCostSourceDigest())
                    || p.spotQuoteAt()==null || p.futuresQuoteAt()==null
                    || p.spotQuoteFirstObservedAt()==null
                    || p.futuresQuoteFirstObservedAt()==null
                    || p.spotFinalFirstAvailableAt()==null
                    || p.futuresFinalFirstAvailableAt()==null
                    || p.cashSessionCloseAt()==null
                    || p.futuresSessionCloseAt()==null
                    || !p.cashSessionCloseAt().equals(
                        FnoExpiryBasisReversionV1.cashClose(p.sessionDate()))
                    || !p.futuresSessionCloseAt().equals(
                        FnoExpiryBasisReversionV1.futuresClose(p.sessionDate()))
                    || p.spotQuoteFirstObservedAt().isAfter(asOf)
                    || p.futuresQuoteFirstObservedAt().isAfter(asOf)
                    || p.spotFinalFirstAvailableAt().isAfter(asOf)
                    || p.futuresFinalFirstAvailableAt().isAfter(asOf)
                    || !calendar.isTradingDay(p.sessionDate())
                    || (previous!=null && !p.sessionDate().isAfter(previous))
                    || (provider!=null && !provider.equals(p.provider()))
                    || (contractHash!=null
                        && !contractHash.equals(p.contractSourceDigest()))
                    || (carryHash!=null
                        && !carryHash.equals(p.carryCostSourceDigest()))
                    || (listedFrom!=null && !listedFrom.equals(p.listedFrom()))
                    || (assumptions!=null && !assumptions.equals(p.assumptions())))
                return blocked("STR180_ORIGINAL_PAIR_PROVENANCE_OR_CARRY_INVALID",
                    sourceRows.size());
            previous=p.sessionDate();
            provider=p.provider();
            contractHash=p.contractSourceDigest();
            carryHash=p.carryCostSourceDigest();
            assumptions=p.assumptions();
            listedFrom=p.listedFrom();
        }

        var five=sourceRows.subList(sourceRows.size()-5,sourceRows.size());
        previous=null;
        for (var p:five) {
            if (previous!=null)
                for(var missing=previous.plusDays(1);
                    missing.isBefore(p.sessionDate());missing=missing.plusDays(1))
                    if(calendar.isTradingDay(missing))
                        return blocked("STR180_EXCHANGE_SESSION_PAIR_GAP",
                            sourceRows.size());
            previous=p.sessionDate();
        }

        var pairs=new ArrayList<FnoExpiryBasisReversionV1.CompletedPair>(5);
        for (var p:five) {
            pairs.add(new FnoExpiryBasisReversionV1.CompletedPair(
                p.sessionDate(),true,equityKey,futuresKey,p.provider(),
                new FnoExpiryBasisReversionV1.Quote(
                    p.spotBid(),p.spotAsk(),p.spotQuoteAt(),
                    p.spotQuoteFirstObservedAt()),
                new FnoExpiryBasisReversionV1.Quote(
                    p.futuresBid(),p.futuresAsk(),p.futuresQuoteAt(),
                    p.futuresQuoteFirstObservedAt()),
                true,true,p.spotFinalFirstAvailableAt(),
                p.futuresFinalFirstAvailableAt()));
        }
        var contract=new FnoExpiryBasisReversionV1.Contract(
            equityKey,futuresKey,equityKey,listedFrom,expiry,lotSize,true,true);
        final FnoExpiryBasisReversionV1.Decision decision;
        try {
            decision=FnoExpiryBasisReversionV1.evaluate(
                contract,List.copyOf(pairs),
                new FnoExpiryBasisReversionV1.SourceRights(true,true,true),
                assumptions,asOf);
        } catch(RuntimeException inputInvalid) {
            return blocked("STR180_EVALUATOR_FAILED_CLOSED",sourceRows.size());
        }
        if (decision==null || decision.status()==null)
            return blocked("STR180_EVALUATOR_UNAVAILABLE",sourceRows.size());
        String state=decision.status().name().startsWith("BLOCKED")
            ? "ORIGINAL_PAIR_EVALUATOR_BLOCKED"
            : "ORIGINAL_PAIR_EVALUATOR_RESEARCH_ONLY";
        return new Result(ID,state,decision.reasonCode(),
            sourceRows.size(),5,localDigest(five),
            decision.status().name(),false,false,false,false);
    }

    private static boolean validHash(String value) {
        return value!=null && value.matches("[a-f0-9]{64}");
    }
    /** Local digest; neither a licence receipt nor proof of realized costs. */
    private static String localDigest(
            List<FnoVerifiedExpiryBasisPairStore.PairProof> pairs) {
        try {
            var sha=MessageDigest.getInstance("SHA-256");
            for(var pair:pairs) {
                var original=pair.sessionDate()+"|"+pair.contractSourceDigest()+
                    "|"+pair.carryCostSourceDigest()+"|"+
                    pair.spotQuoteAt()+"|"+pair.futuresQuoteAt()+"\n";
                sha.update(original.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch(NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE",impossible);
        }
    }
    private static Result blocked(String reason,int rows) {
        return new Result(ID,"BLOCKED_NOT_HISTORICAL_E2E",reason,
            rows,0,null,"NOT_RUN",false,false,false,false);
    }
}
