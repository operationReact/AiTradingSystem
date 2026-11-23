package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.provider.upstox.FuturesInstrumentMasterFetcher;
import in.aitradingsystem.provider.upstox.UpstoxFuturesInstrumentMaster;
import in.aitradingsystem.provider.upstox.UpstoxFnoInstrumentUniverse;
import in.aitradingsystem.provider.upstox.UpstoxFuturesInstrumentMaster.FuturesContract;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Canonical NSE_FO futures contract registry, built from the published Upstox
 * instrument master. Refreshed on a schedule and only when the contract-discovery
 * permission flag is verified. Failed refreshes keep the last snapshot but report
 * the failure, and the snapshot age is visible to observers.
 */
@Component
public class FnoFuturesContractRegistry {
    static final String NIFTY = "NSE_INDEX|Nifty 50";
    static final int MIN_NEAR_CALENDAR_DAYS = 3;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record Snapshot(Instant fetchedAt, String sha256, int currentFutRows,
                           Map<String, Map<LocalDate, FuturesContract>> byUnderlying) {}

    public record NearFar(FuturesContract near, FuturesContract far) {}

    /** Full BOD identities, not V3 subscriptions, prices or order permissions. */
    public record UniverseSnapshot(Instant fetchedAt, String sha256,
                                   UpstoxFnoInstrumentUniverse.Catalog catalog) {}

    public record UniverseStatus(String state, String lastFailure,
            Instant fetchedAt, String sha256, int contracts,
            int futures, int calls, int puts, int underlyings) {}

    public record UniverseResult(UniverseStatus status, int totalMatches,
            List<UpstoxFnoInstrumentUniverse.Contract> contracts) {}

    public record Status(String state, String lastFailure, Instant fetchedAt, String sha256,
                         int currentFutRows, int underlyings) {}

    private final FuturesInstrumentMasterFetcher fetcher;
    private final Clock clock;
    private final boolean discoveryVerified;
    private volatile Snapshot snapshot;
    private volatile String lastFailure;
    private volatile UniverseSnapshot universeSnapshot;
    private volatile String universeFailure;
    private volatile Instant lastRefreshAttempt = Instant.EPOCH;

    @Autowired
    public FnoFuturesContractRegistry(FuturesInstrumentMasterFetcher fetcher,
            @Value("${minttick.fno-research.contract-discovery-verified:false}")
            boolean discoveryVerified,
            @Value("${minttick.upstox-fno.display-rights-verified:false}")
            boolean displayRightsVerified) {
        // Mirror customer F&O discovery: independently verified display rights
        // INCLUDE listed identity discovery. This does not confer retention,
        // training, subscription, broker or LIVE order rights.
        this(fetcher, Clock.systemUTC(),
            discoveryVerified || displayRightsVerified);
    }

    public FnoFuturesContractRegistry(FuturesInstrumentMasterFetcher fetcher,
            boolean discoveryVerified) {
        this(fetcher, Clock.systemUTC(), discoveryVerified);
    }

    FnoFuturesContractRegistry(FuturesInstrumentMasterFetcher fetcher, Clock clock, boolean discoveryVerified) {
        this.fetcher = fetcher;
        this.clock = clock;
        this.discoveryVerified = discoveryVerified;
    }

    /** Scheduled refresh; a no-op unless contract discovery is verified. */
    @Scheduled(cron = "0 30 8 * * *", zone = "Asia/Kolkata")
    public void scheduledRefresh() {
        refresh();
    }

    /**
     * A post-close deploy must not wait until the next 08:30 BOD job before
     * the universe exists. Bootstrap once on startup, with a 30-minute retry
     * floor if the public file is temporarily unavailable. Never fetch data
     * without the existing independently authorized discovery feature flag.
     * Does not touch, start or stop the live WebSocket.
     */
    @Scheduled(
        initialDelayString = "${minttick.fno-research.master-bootstrap-delay-ms:15000}",
        fixedDelayString = "${minttick.fno-research.master-bootstrap-check-ms:1800000}")
    public synchronized void bootstrapIfNeeded() {
        if (!discoveryVerified) return;
        Instant now = clock.instant();
        var current = universeSnapshot;
        boolean freshToday = current != null && universeFailure == null &&
            current.fetchedAt().atZone(IST).toLocalDate()
                .equals(now.atZone(IST).toLocalDate());
        if (freshToday || now.isBefore(lastRefreshAttempt.plus(
                java.time.Duration.ofMinutes(30)))) return;
        refresh();
    }

    public synchronized void refresh() {
        if (!discoveryVerified) {
            lastFailure = "CONTRACT_DISCOVERY_NOT_VERIFIED";
            universeFailure = "CONTRACT_DISCOVERY_NOT_VERIFIED";
            return;
        }
        Instant now = clock.instant();
        lastRefreshAttempt = now;
        try {
            var fetched = fetcher.fetch();
            LocalDate today = now.atZone(IST).toLocalDate();
            List<FuturesContract> contracts = UpstoxFuturesInstrumentMaster.parse(
                new ByteArrayInputStream(fetched.bytes()), today);
            var index = UpstoxFuturesInstrumentMaster.index(contracts);
            snapshot = new Snapshot(now, fetched.sha256(), contracts.size(), index);
            lastFailure = null;
            // One signed source file / digest for both futures and options.
            // A CE/PE schema problem cannot corrupt a good FUT snapshot.
            try {
                var universe = UpstoxFnoInstrumentUniverse.parse(
                    new ByteArrayInputStream(fetched.bytes()), today);
                universeSnapshot = new UniverseSnapshot(now, fetched.sha256(), universe);
                universeFailure = null;
            } catch (Exception malformedOptions) {
                universeFailure = "FNO_UNIVERSE_UNVERIFIED_SCHEMA";
            }
        } catch (Exception e) {
            // Only stable codes are kept; provider bodies and exception text are not.
            lastFailure = e instanceof UpstoxFuturesInstrumentMaster.MalformedInstrumentMasterException
                ? "INSTRUMENT_MASTER_MALFORMED" : "INSTRUMENT_MASTER_FETCH_FAILED";
            universeFailure = "INSTRUMENT_MASTER_FETCH_FAILED";
        }
    }

    /**
     * Nearest listed expiry at least {@value #MIN_NEAR_CALENDAR_DAYS} days away and the
     * next expiry, both with identical lot size. Empty when the pair is not verifiable.
     */
    public Optional<NearFar> nearFar(String underlyingKey, LocalDate today) {
        Snapshot s = snapshot;
        if (s == null || underlyingKey == null) return Optional.empty();
        Map<LocalDate, FuturesContract> byExpiry = s.byUnderlying().get(underlyingKey);
        if (byExpiry == null) return Optional.empty();
        var eligible = byExpiry.entrySet().stream()
            .filter(e -> !e.getKey().isBefore(today.plusDays(MIN_NEAR_CALENDAR_DAYS)))
            .map(Map.Entry::getValue).toList();
        if (eligible.size() < 2) return Optional.empty();
        FuturesContract near = eligible.get(0);
        FuturesContract far = eligible.get(1);
        if (near.lotSize() != far.lotSize()) return Optional.empty();
        return Optional.of(new NearFar(near, far));
    }

    public Status status() {
        Snapshot s = snapshot;
        String state = s == null ? (discoveryVerified ? "NOT_LOADED" : "DISCOVERY_NOT_VERIFIED")
            : lastFailure == null ? "LOADED" : "STALE_LAST_REFRESH_FAILED";
        return new Status(state, lastFailure, s == null ? null : s.fetchedAt(),
            s == null ? null : s.sha256(), s == null ? 0 : s.currentFutRows(),
            s == null ? 0 : s.byUnderlying().size());
    }

    public UniverseStatus universeStatus() {
        UniverseSnapshot s = universeSnapshot;
        var c = s == null ? null : s.catalog();
        boolean expired = s != null &&
            (clock.instant().isBefore(s.fetchedAt()) ||
                clock.instant().isAfter(s.fetchedAt().plus(java.time.Duration.ofHours(36))));
        String state = !discoveryVerified ? "DISCOVERY_NOT_VERIFIED"
            : s == null ? "NOT_LOADED"
            : expired ? "STALE_SNAPSHOT"
            : universeFailure == null ? "LOADED" : "STALE_LAST_REFRESH_FAILED";
        return new UniverseStatus(state, universeFailure,
            s == null ? null : s.fetchedAt(),
            s == null ? null : s.sha256(),
            c == null ? 0 : c.size(),
            c == null ? 0 : c.futures(),
            c == null ? 0 : c.calls(),
            c == null ? 0 : c.puts(),
            c == null ? 0 : c.underlyings());
    }

    /**
     * Safe paginated discovery; returns no contracts when the independent
     * discovery entitlement is absent or the daily file is too old. The
     * caller must not turn this into quote freshness or order permission.
     */
    /** Exact-key identity lookup, guarded by source rights and master age. */
    public Optional<UpstoxFnoInstrumentUniverse.Contract> listedInstrument(String key) {
        if (key == null || !key.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}"))
            return Optional.empty();
        var status = universeStatus();
        if (!("LOADED".equals(status.state()) ||
                "STALE_LAST_REFRESH_FAILED".equals(status.state())))
            return Optional.empty();
        return Optional.ofNullable(universeSnapshot.catalog().byInstrumentKey().get(key));
    }

    public UniverseResult universe(String underlyingKey, LocalDate expiry,
            String type, int page, int pageSize) {
        UniverseStatus state = universeStatus();
        if (!("LOADED".equals(state.state()) ||
                "STALE_LAST_REFRESH_FAILED".equals(state.state())))
            return new UniverseResult(state, 0, List.of());
        UpstoxFnoInstrumentUniverse.Catalog c = universeSnapshot.catalog();
        var rows = c.query(underlyingKey, expiry, type, page, pageSize);
        return new UniverseResult(state, rows.total(), rows.contracts());
    }

    /** Search over the already-loaded provider file; never perform a provider GET. */
    public UniverseResult universeSearch(String query, String type,
            int page, int pageSize) {
        UniverseStatus state = universeStatus();
        if (!("LOADED".equals(state.state()) ||
                "STALE_LAST_REFRESH_FAILED".equals(state.state())))
            return new UniverseResult(state, 0, List.of());
        var result = universeSnapshot.catalog().search(query, type, page, pageSize);
        return new UniverseResult(state, result.total(), result.contracts());
    }

    private static byte[] gunzipIfNeeded(byte[] bytes) throws java.io.IOException {
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            try (var in = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return in.readAllBytes();
            }
        }
        return bytes;
    }
}
