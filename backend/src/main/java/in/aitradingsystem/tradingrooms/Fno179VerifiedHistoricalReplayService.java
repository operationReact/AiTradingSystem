package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedIntradayLiquidityStore;
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
 * STR-179 rights-attested source to actual historical liquidity evaluator.
 * Only original intraday L1, independent OI and volume snapshots are accepted.
 * The source SQL requires verified MODEL_INFERENCE rights at the historical
 * as-of; this never falls back to an OHLCV candle, LTP or synthetic depth.
 * One transient 90-second lookback is NOT an after-cost backtest or E2E fill.
 */
@Service
public class Fno179VerifiedHistoricalReplayService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String ID = FnoContractLiquidityGateV1.STRATEGY_ID;
    private static final FnoContractLiquidityGateV1.Thresholds THRESHOLDS =
        new FnoContractLiquidityGateV1.Thresholds(100,20,2,20,50,5,10,30,500);

    private final FnoVerifiedIntradayLiquidityStore source;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean researchRights, liquidityRights, discoveryRights;

    @Autowired
    public Fno179VerifiedHistoricalReplayService(
            FnoVerifiedIntradayLiquidityStore source,
            MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.research-rights-verified:false}")
            boolean researchRights,
            @Value("${minttick.fno-research.liquidity-research-rights-verified:false}")
            boolean liquidityRights,
            @Value("${minttick.fno-research.contract-discovery-verified:false}")
            boolean discoveryRights) {
        this(source,calendar,Clock.systemUTC(),
            researchRights,liquidityRights,discoveryRights);
    }

    Fno179VerifiedHistoricalReplayService(
            FnoVerifiedIntradayLiquidityStore source,
            MarketSessionCalendar calendar, Clock clock,
            boolean researchRights, boolean liquidityRights, boolean discoveryRights) {
        this.source=Objects.requireNonNull(source);
        this.calendar=Objects.requireNonNull(calendar);
        this.clock=Objects.requireNonNull(clock);
        this.researchRights=researchRights;
        this.liquidityRights=liquidityRights;
        this.discoveryRights=discoveryRights;
    }

    public record Result(
        String strategyId, String state, String reasonCode,
        int sourceRows, int originalQuoteObservations, String sourceInputDigest,
        String evaluatorStatus, boolean independentHistoricalE2E,
        boolean paperLedgerE2E, boolean liveBrokerE2E, boolean ordersPlaced) {}

    /** Original provider snapshots cannot have been captured after asOf. */
    public Result inspect(String key, LocalDate expiry, int lotSize, Instant asOf) {
        if (!researchRights || !liquidityRights || !discoveryRights)
            return blocked("STR179_SOURCE_RIGHTS_OR_DISCOVERY_UNVERIFIED",0);
        if (key == null || !key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || asOf == null || asOf.isAfter(clock.instant())
                || asOf.isBefore(clock.instant().minus(366,ChronoUnit.DAYS))
                || expiry == null || expiry.isBefore(asOf.atZone(IST).toLocalDate())
                || lotSize < 1 || lotSize > 100000)
            return blocked("STR179_SCOPE_INVALID",0);
        if (!calendar.isInsideFnoSession(asOf))
            return blocked("STR179_ORIGINAL_FNO_EXCHANGE_SESSION_UNVERIFIED",0);

        final List<FnoVerifiedIntradayLiquidityStore.ReplayProof> rows;
        try {
            rows = source.latestThreeReplayFutures(key,expiry,lotSize,asOf);
        } catch (RuntimeException unavailable) {
            return blocked("STR179_SOURCE_READ_UNAVAILABLE",0);
        }
        if (rows == null || rows.size()!=3)
            return blocked("STR179_THREE_ORIGINAL_QUOTE_EVENTS_MISSING",
                rows == null ? 0 : rows.size());

        var snapshots = new ArrayList<FnoContractLiquidityGateV1.MarketSnapshot>(3);
        Instant previous = null;
        String provider = null, underlying = null, contractDigest = null,
            sessionDigest = null;
        for (var p : rows) {
            var v = p == null ? null : p.snapshot();
            if (v == null || !"UPSTOX_V3".equals(v.provider())
                    || !key.equals(v.instrumentKey()) || v.underlyingKey() == null
                    || !expiry.equals(v.expiry()) || lotSize!=v.lotSize()
                    || !"FUTURE".equals(v.kind()) || !validHash(p.sessionSourceSha256())
                    || !validHash(p.contractSourceSha256())
                    || !validHash(p.quoteSourceSha256())
                    || !validHash(p.oiSourceSha256())
                    || !validHash(p.volumeSourceSha256())
                    || (contractDigest!=null
                        && !contractDigest.equals(p.contractSourceSha256()))
                    || (sessionDigest!=null
                        && !sessionDigest.equals(p.sessionSourceSha256()))
                    || (underlying!=null && !underlying.equals(v.underlyingKey()))
                    || (provider!=null && !provider.equals(v.provider()))
                    || v.exchangeAt()==null || v.observedAt()==null
                    || v.oiFirstObservedAt()==null || v.volumeFirstObservedAt()==null
                    || !v.exchangeAt().atZone(IST).toLocalDate()
                        .equals(asOf.atZone(IST).toLocalDate())
                    || v.exchangeAt().isAfter(asOf)
                    || v.observedAt().isBefore(v.exchangeAt())
                    || v.observedAt().isAfter(asOf)
                    || v.oiFirstObservedAt().isAfter(v.observedAt())
                    || v.volumeFirstObservedAt().isAfter(v.observedAt())
                    || (previous!=null && !v.exchangeAt().isAfter(previous)))
                return blocked("STR179_ORIGINAL_SOURCE_CUSTODY_OR_SEQUENCE_INVALID",3);
            provider=v.provider();
            underlying=v.underlyingKey();
            sessionDigest=p.sessionSourceSha256();
            contractDigest=p.contractSourceSha256();
            previous=v.exchangeAt();
            snapshots.add(new FnoContractLiquidityGateV1.MarketSnapshot(
                v.instrumentKey(),v.provider(),v.exchangeAt(),v.observedAt(),
                v.bid(),v.ask(),v.bidDepthUnits(),v.askDepthUnits(),
                v.oiUnits(),v.cumulativeVolumeUnits(),
                v.oiFirstObservedAt(),v.volumeFirstObservedAt(),
                true,true,true,true));
        }

        var contract = new FnoContractLiquidityGateV1.Contract(
            key,underlying,expiry,lotSize,
            FnoContractLiquidityGateV1.DerivativeKind.FUTURE,null,true);
        final FnoContractLiquidityGateV1.Decision decision;
        try {
            decision = FnoContractLiquidityGateV1.evaluate(
                contract,List.copyOf(snapshots),
                new FnoContractLiquidityGateV1.SourceRights(true,true,true,true),
                THRESHOLDS,asOf);
        } catch (RuntimeException rejected) {
            return blocked("STR179_SOURCE_EVALUATOR_FAILED_CLOSED",3);
        }
        if (decision == null || decision.status() == null)
            return blocked("STR179_NO_VALID_EVALUATOR_RESULT",3);
        var state = decision.status().name().startsWith("BLOCKED")
            ? "ORIGINAL_SOURCE_EVALUATOR_BLOCKED"
            : "ORIGINAL_SOURCE_RESEARCH_ONLY";
        return new Result(ID,state,decision.reasonCode(),3,3,
            digest(rows),decision.status().name(),false,false,false,false);
    }

    private static boolean validHash(String value) {
        return value!=null && value.matches("[a-f0-9]{64}");
    }

    /** Private derived checksum only, NOT a provider-issued rights receipt. */
    private static String digest(List<FnoVerifiedIntradayLiquidityStore.ReplayProof> rows) {
        try {
            var sha=MessageDigest.getInstance("SHA-256");
            for (var row:rows) {
                var datum = row.snapshot().exchangeAt() + "|" +
                    row.sessionSourceSha256() + "|" +
                    row.contractSourceSha256() + "|" +
                    row.quoteSourceSha256() + "|" +
                    row.oiSourceSha256() + "|" + row.volumeSourceSha256()+"\n";
                sha.update(datum.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE",impossible);
        }
    }
    private static Result blocked(String reason, int count) {
        return new Result(ID,"BLOCKED_NOT_HISTORICAL_E2E",
            reason,count,0,null,"NOT_RUN",false,false,false,false);
    }
}
