package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.marketdata.FnoVerifiedCompletedOhlcvOiStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Original source/rights-joined V102 history -> actual STR-177 futures
 * ten-session mean-reversion evaluator. No midnight-labelled SQLite source,
 * invented original timestamps, public endpoint, or trading orders.
 *
 * Each original completed OHLCV/OI V102 row was joined, at the historical
 * asOf, to independent VERIFIED MODEL_INFERENCE rights receipts and a
 * rights-attested original NSE session calendar in the shared SQL store.
 * The output is RESEARCH ONLY; independent market-data/OOS backtest
 * acceptance and PAPER/LIVE execution are not claimed.
 */
@Service
public final class Fno177VerifiedHistoricalReplayService {
    public static final String STRATEGY = FnoFuturesMeanReversionV1.STRATEGY_ID;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int REQUIRED_SESSIONS = 10;
    private static final int CALENDAR_LOOKBACK = 30;
    private static final FnoFuturesMeanReversionV1.Thresholds RESEARCH_THRESHOLDS =
        new FnoFuturesMeanReversionV1.Thresholds(
            25, 200, 60, 100, 25, 9000, 1500, 1000, 900, 5000);
    private final FnoVerifiedCompletedOhlcvOiStore history;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean researchUse, completedHistoryUse, discoveryUse;

    @Autowired
    public Fno177VerifiedHistoricalReplayService(
            FnoVerifiedCompletedOhlcvOiStore history,
            MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.research-rights-verified:false}")
            boolean researchUse,
            @Value("${minttick.fno-research.completed-ohlcv-research-rights-verified:false}")
            boolean completedHistoryUse,
            @Value("${minttick.fno-research.contract-discovery-verified:false}")
            boolean discoveryUse) {
        this(history, calendar, Clock.systemUTC(),
            researchUse, completedHistoryUse, discoveryUse);
    }
    Fno177VerifiedHistoricalReplayService(
            FnoVerifiedCompletedOhlcvOiStore history,
            MarketSessionCalendar calendar, Clock clock,
            boolean researchUse, boolean completedHistoryUse,
            boolean discoveryUse) {
        this.history = Objects.requireNonNull(history);
        this.calendar = Objects.requireNonNull(calendar);
        this.clock = Objects.requireNonNull(clock);
        this.researchUse = researchUse;
        this.completedHistoryUse = completedHistoryUse;
        this.discoveryUse = discoveryUse;
    }

    public record Result(
        String strategyId, String state, String evaluatorReasonCode,
        int originalRowsObserved, int verifiedCompletedSessions,
        String originalSourceInputDigest, String evaluatorStatus,
        boolean independentlyVerifiedHistoricalE2E,
        boolean paperLedgerE2E, boolean liveBrokerE2E,
        boolean brokerOrdersPlaced) {}

    public Result inspect(String contractKey, Instant asOf) {
        if (!researchUse || !completedHistoryUse || !discoveryUse)
            return blocked("STR177_RESEARCH_SOURCE_OR_DISCOVERY_RIGHTS_MISSING",0);
        if (contractKey == null
                || !contractKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || asOf == null || asOf.isAfter(clock.instant()))
            return blocked("STR177_REPLAY_SCOPE_INVALID",0);

        LocalDate to = asOf.atZone(IST).toLocalDate().minusDays(1);
        LocalDate from = to.minusDays(CALENDAR_LOOKBACK);
        final List<FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar> rows;
        try {
            rows = history.readExactReplayContract(contractKey,from,to,asOf);
        } catch (RuntimeException inaccessible) {
            // Never reveal source SQL or position information in exception text.
            return blocked("STR177_ORIGINAL_RIGHTS_JOIN_SOURCE_UNAVAILABLE",0);
        }
        if (rows == null || rows.size() < REQUIRED_SESSIONS)
            return blocked("STR177_TEN_RIGHTS_ATTESTED_SESSIONS_MISSING",
                rows == null ? 0 : rows.size());
        if (rows.size() > CALENDAR_LOOKBACK + 1)
            return blocked("STR177_UNBOUNDED_OR_AMBIGUOUS_SOURCE",0);

        FnoVerifiedCompletedOhlcvOiStore.VerifiedBar identity = null;
        String provider = null;
        String listingDigest = null;
        LocalDate previous = null;
        for (var proof : rows) {
            var b = proof == null ? null : proof.bar();
            if (b == null || b.sessionDate() == null
                    || !contractKey.equals(b.instrumentKey())
                    || b.provider() == null || !List.of(
                        "UPSTOX_ANALYTICS","ANGEL_ONE_SMARTAPI").contains(b.provider())
                    || b.underlyingKey() == null
                    || b.listedFrom() == null || b.expiry() == null
                    || b.lotSize() < 1 || b.lotSize() > 100000
                    || b.sessionDate().isBefore(b.listedFrom())
                    || !b.sessionDate().isBefore(b.expiry())
                    || !b.sessionDate().isBefore(asOf.atZone(IST).toLocalDate())
                    || b.verifiedSessionCloseAt() == null
                    || b.ohlcvFirstAvailableAt() == null
                    || b.oiFirstAvailableAt() == null
                    || b.ohlcvFirstAvailableAt().isBefore(b.verifiedSessionCloseAt())
                    || b.oiFirstAvailableAt().isBefore(b.verifiedSessionCloseAt())
                    || b.ohlcvFirstAvailableAt().isAfter(asOf)
                    || b.oiFirstAvailableAt().isAfter(asOf)
                    || !calendar.isTradingDay(b.sessionDate())
                    || !validDigest(proof.originalOhlcvDigest())
                    || !validDigest(proof.originalOiDigest())
                    || !validDigest(proof.originalListingDigest())
                    || (previous != null && !b.sessionDate().isAfter(previous))
                    || (provider != null && !provider.equals(b.provider()))
                    || (listingDigest != null
                        && !listingDigest.equals(proof.originalListingDigest()))
                    || (identity != null && (!identity.underlyingKey().equals(b.underlyingKey())
                        || !identity.listedFrom().equals(b.listedFrom())
                        || !identity.expiry().equals(b.expiry())
                        || identity.lotSize() != b.lotSize())))
                return blocked("STR177_CONTRACT_CALENDAR_OR_CUSTODY_INVALID",rows.size());
            identity = b;
            previous = b.sessionDate();
            provider = b.provider();
            listingDigest = proof.originalListingDigest();
        }

        var ten = rows.subList(rows.size() - REQUIRED_SESSIONS,rows.size());
        previous = null;
        for (var proof : ten) {
            LocalDate day=proof.bar().sessionDate();
            if (previous != null)
                for (LocalDate missing=previous.plusDays(1);
                        missing.isBefore(day);missing=missing.plusDays(1))
                    if (calendar.isTradingDay(missing))
                        return blocked("STR177_VERIFIED_EXCHANGE_SESSION_GAP",rows.size());
            previous = day;
        }

        var rawContract = identity;
        var contract = new FnoFuturesMeanReversionV1.Contract(
            rawContract.instrumentKey(),rawContract.underlyingKey(),
            rawContract.listedFrom(),rawContract.expiry(),
            rawContract.lotSize(),true);
        var historySessions=new ArrayList<FnoFuturesMeanReversionV1.CompletedSession>();
        for (var proof : ten) {
            var b=proof.bar();
            historySessions.add(new FnoFuturesMeanReversionV1.CompletedSession(
                b.sessionDate(),true,b.instrumentKey(),b.provider(),
                b.open(),b.high(),b.low(),b.close(),true,
                b.ohlcvFirstAvailableAt(),b.tradedVolumeUnits(),
                b.openInterestUnits(),b.oiFirstAvailableAt()));
        }
        // Original rights were verified by the SQL JOIN; this local research
        // call still cannot independently prove historical after-cost outcomes.
        final FnoFuturesMeanReversionV1.Decision decision;
        try {
            decision = FnoFuturesMeanReversionV1.evaluate(contract,
                List.copyOf(historySessions),
                new FnoFuturesMeanReversionV1.SourceRights(true,true,true),
                RESEARCH_THRESHOLDS,asOf);
        } catch (RuntimeException malformed) {
            return blocked("STR177_EVALUATOR_FAILED_CLOSED",rows.size());
        }
        if (decision == null || decision.status() == null)
            return blocked("STR177_EVALUATOR_RESULT_MISSING",rows.size());
        String state = decision.status().name().startsWith("BLOCKED")
            ? "ORIGINAL_SOURCE_EVALUATOR_BLOCKED"
            : "ORIGINAL_SOURCE_EVALUATOR_RESEARCH_ONLY";
        return new Result(STRATEGY,state,decision.reasonCode(),
            rows.size(),REQUIRED_SESSIONS,hashSource(ten),
            decision.status().name(),false,false,false,false);
    }

    private static boolean validDigest(String hash) {
        return hash != null && hash.matches("[0-9a-f]{64}");
    }
    private static String hashSource(
            List<FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar> rows) {
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            for (var row : rows) {
                var record = row.bar().sessionDate()+"|"+
                    row.originalOhlcvDigest()+"|"+
                    row.originalOiDigest()+"|"+
                    row.originalListingDigest()+"\n";
                hash.update(record.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE",impossible);
        }
    }
    private static Result blocked(String reason,int originalRows) {
        return new Result(STRATEGY,"BLOCKED_NOT_HISTORICAL_E2E",
            reason,originalRows,0,null,"NOT_RUN",
            false,false,false,false);
    }
}
