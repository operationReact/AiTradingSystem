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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Original rights-attested V102 PostgreSQL evidence -> actual STR-176 evaluator.
 * No EOD midnight labels, fabricated close L1 ticks, source export or orders.
 * Source custody and research-use rights remain SQL-joined at the historical
 * decision as-of; structural replay is never full after-cost market E2E.
 */
@Service
public final class Fno176VerifiedHistoricalReplayService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String STRATEGY = FnoFuturesMomentumV1.STRATEGY_ID;
    private final FnoVerifiedCompletedOhlcvOiStore source;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final boolean approvedResearch, approvedCompletedSource, approvedDiscovery;

    @Autowired
    public Fno176VerifiedHistoricalReplayService(
            FnoVerifiedCompletedOhlcvOiStore source, MarketSessionCalendar calendar,
            @Value("${minttick.fno-research.research-rights-verified:false}")
            boolean approvedResearch,
            @Value("${minttick.fno-research.completed-ohlcv-research-rights-verified:false}")
            boolean approvedCompletedSource,
            @Value("${minttick.fno-research.contract-discovery-verified:false}")
            boolean approvedDiscovery) {
        this(source, calendar, Clock.systemUTC(), approvedResearch,
            approvedCompletedSource, approvedDiscovery);
    }

    Fno176VerifiedHistoricalReplayService(
            FnoVerifiedCompletedOhlcvOiStore source, MarketSessionCalendar calendar,
            Clock clock, boolean approvedResearch,
            boolean approvedCompletedSource, boolean approvedDiscovery) {
        this.source = Objects.requireNonNull(source);
        this.calendar = Objects.requireNonNull(calendar);
        this.clock = Objects.requireNonNull(clock);
        this.approvedResearch = approvedResearch;
        this.approvedCompletedSource = approvedCompletedSource;
        this.approvedDiscovery = approvedDiscovery;
    }

    public record Evidence(
        String strategyId, String state, String reasonCode,
        int originalRows, int sessionWindow,
        FnoFuturesHistoricalReplayBridgeV1.ReplayReport replay,
        boolean independentlyVerifiedHistoricalE2E,
        boolean paperTradingReady, boolean liveTradingReady,
        boolean brokerOrdersPlaced) {}

    public Evidence inspect(String key, Instant asOf) {
        if (!approvedResearch || !approvedCompletedSource || !approvedDiscovery)
            return denied("STR176_SOURCE_RIGHTS_OR_LISTING_UNVERIFIED", 0);
        if (key == null || !key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || asOf == null || asOf.isAfter(clock.instant())
                || asOf.isBefore(clock.instant().minus(366, ChronoUnit.DAYS)))
            return denied("STR176_DECISION_SCOPE_INVALID", 0);
        LocalDate last = asOf.atZone(IST).toLocalDate().minusDays(1);
        LocalDate first = last.minusDays(25);
        final List<FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar> rows;
        try {
            // The SQL requires original rights verified_at/captured_at <= asOf,
            // original available times <= asOf, and retention still in scope.
            rows = source.readExactReplayContract(key, first, last, asOf);
        } catch (RuntimeException unavailable) {
            return denied("STR176_ORIGINAL_SOURCE_READ_UNAVAILABLE", 0);
        }
        if (rows == null || rows.size() < 7)
            return denied("STR176_SEVEN_SOURCE_SESSIONS_MISSING",
                rows == null ? 0 : rows.size());
        if (rows.size() > 26) return denied("STR176_SOURCE_WINDOW_UNBOUNDED", 0);

        FnoVerifiedCompletedOhlcvOiStore.VerifiedBar contract = null;
        String provider = null, listingDigest = null;
        LocalDate prior = null;
        for (var proof : rows) {
            var b = proof == null ? null : proof.bar();
            if (b == null || b.sessionDate() == null || b.instrumentKey() == null
                    || !key.equals(b.instrumentKey()) || b.underlyingKey() == null
                    || b.listedFrom() == null || b.expiry() == null
                    || b.lotSize() < 1 || b.lotSize() > 100000
                    || b.sessionDate().isBefore(b.listedFrom())
                    || !b.sessionDate().isBefore(b.expiry())
                    || !b.sessionDate().isBefore(asOf.atZone(IST).toLocalDate())
                    || b.provider() == null || !List.of(
                        "UPSTOX_ANALYTICS","ANGEL_ONE_SMARTAPI").contains(b.provider())
                    || b.verifiedSessionCloseAt() == null
                    || b.ohlcvFirstAvailableAt() == null
                    || b.oiFirstAvailableAt() == null
                    || b.ohlcvFirstAvailableAt().isAfter(asOf)
                    || b.oiFirstAvailableAt().isAfter(asOf)
                    || b.ohlcvFirstAvailableAt().isBefore(b.verifiedSessionCloseAt())
                    || b.oiFirstAvailableAt().isBefore(b.verifiedSessionCloseAt())
                    || !digest(proof.originalOhlcvDigest())
                    || !digest(proof.originalOiDigest())
                    || !digest(proof.originalListingDigest())
                    || (prior != null && !b.sessionDate().isAfter(prior))
                    || (provider != null && !provider.equals(b.provider()))
                    || (listingDigest != null
                        && !listingDigest.equals(proof.originalListingDigest()))
                    || (contract != null && (!contract.underlyingKey().equals(b.underlyingKey())
                        || !contract.listedFrom().equals(b.listedFrom())
                        || !contract.expiry().equals(b.expiry())
                        || contract.lotSize() != b.lotSize())))
                return denied("STR176_ORIGINAL_CONTRACT_OR_CUSTODY_INCONSISTENT", rows.size());
            // A provider/calendar outage is UNKNOWN, never proof of a holiday
            // or a source-verified trading session.
            try {
                if (!calendar.isTradingDay(b.sessionDate()))
                    return denied("STR176_ORIGINAL_CONTRACT_OR_CUSTODY_INCONSISTENT",
                        rows.size());
            } catch (RuntimeException unavailable) {
                return denied("STR176_VERIFIED_CALENDAR_UNAVAILABLE", 0);
            }
            contract = b;
            provider = b.provider();
            listingDigest = proof.originalListingDigest();
            prior = b.sessionDate();
        }

        var lastSeven = rows.subList(rows.size() - 7, rows.size());
        prior = null;
        for (var proof : lastSeven) {
            var day = proof.bar().sessionDate();
            if (prior != null) for (var gap = prior.plusDays(1);
                    gap.isBefore(day); gap = gap.plusDays(1)) {
                final boolean sessionExpected;
                try {
                    sessionExpected = calendar.isTradingDay(gap);
                } catch (RuntimeException unavailable) {
                    return denied("STR176_VERIFIED_CALENDAR_UNAVAILABLE", 0);
                }
                if (sessionExpected)
                    return denied("STR176_VERIFIED_CALENDAR_SESSION_GAP",rows.size());
            }
            prior = day;
        }

        // Seven consecutive bars are insufficient if a more recent completed
        // exchange session is absent. Reject stale-but-in-range windows instead
        // of scoring an earlier breakout as if it were the latest decision.
        for (var gap = prior.plusDays(1); !gap.isAfter(last); gap = gap.plusDays(1)) {
            final boolean sessionExpected;
            try {
                sessionExpected = calendar.isTradingDay(gap);
            } catch (RuntimeException unavailable) {
                return denied("STR176_VERIFIED_CALENDAR_UNAVAILABLE", 0);
            }
            if (sessionExpected)
                return denied("STR176_VERIFIED_CALENDAR_SESSION_GAP", rows.size());
        }

        List<FnoFuturesHistoricalReplayBridgeV1.HistoricalBar> history = new ArrayList<>();
        for (var proof : lastSeven) {
            var b = proof.bar();
            history.add(new FnoFuturesHistoricalReplayBridgeV1.HistoricalBar(
                b.sessionDate(),b.instrumentKey(),b.provider(),true,true,
                null, // V102 has NO original near-close L1 quote for STR-173.
                b.ohlcvFirstAvailableAt(),b.oiFirstAvailableAt(),
                b.open(),b.high(),b.low(),b.close(),
                b.tradedVolumeUnits(),b.openInterestUnits()));
        }
        var c = contract;
        var identity = new FnoFuturesHistoricalReplayBridgeV1.ContractIdentity(
            c.instrumentKey(),c.underlyingKey(),c.listedFrom(),c.expiry(),
            c.lotSize(),true);
        var rights = new FnoFuturesHistoricalReplayBridgeV1.EvidenceRights(
            true,true,true,true,true,true);
        var window = new FnoFuturesHistoricalReplayBridgeV1.Window(
            asOf,identity,rights,digestOfOriginalHashes(lastSeven),
            List.copyOf(history));
        var replay = FnoFuturesHistoricalReplayBridgeV1.replay(
            STRATEGY,List.of(window));
        return new Evidence(STRATEGY,
            "EVALUATOR_REPLAY_RESEARCH_ONLY_NOT_E2E".equals(replay.state())
                ? "SOURCE_JOINED_EVALUATOR_RAN"
                : "SOURCE_JOINED_EVALUATOR_BLOCKED",
            replay.reasonCode(),rows.size(),lastSeven.size(),replay,
            false,false,false,false);
    }

    private static boolean digest(String s) {
        return s != null && s.matches("[a-f0-9]{64}");
    }
    /** Derived input checksum, NOT another provider-signed source receipt. */
    private static String digestOfOriginalHashes(
            List<FnoVerifiedCompletedOhlcvOiStore.ReplayProofBar> rows) {
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            for (var r : rows) {
                var value = r.bar().sessionDate() + "|" +
                    r.originalOhlcvDigest() + "|" + r.originalOiDigest() +
                    "|" + r.originalListingDigest() + "\n";
                hash.update(value.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("HASH_SOURCE_NOT_AVAILABLE",impossible);
        }
    }
    private static Evidence denied(String reason,int rows) {
        return new Evidence(STRATEGY,"BLOCKED_NOT_HISTORICAL_E2E",
            reason,rows,0,null,false,false,false,false);
    }
}
