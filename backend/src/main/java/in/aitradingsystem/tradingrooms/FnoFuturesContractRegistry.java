package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.provider.upstox.FuturesInstrumentMasterFetcher;
import in.aitradingsystem.provider.upstox.UpstoxFuturesInstrumentMaster;
import in.aitradingsystem.provider.upstox.UpstoxFuturesInstrumentMaster.FuturesContract;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public record Status(String state, String lastFailure, Instant fetchedAt, String sha256,
                         int currentFutRows, int underlyings) {}

    private final FuturesInstrumentMasterFetcher fetcher;
    private final Clock clock;
    private final boolean discoveryVerified;
    private volatile Snapshot snapshot;
    private volatile String lastFailure;

    public FnoFuturesContractRegistry(FuturesInstrumentMasterFetcher fetcher,
            @Value("${minttick.fno-research.contract-discovery-verified:false}") boolean discoveryVerified) {
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

    public synchronized void refresh() {
        if (!discoveryVerified) {
            lastFailure = "CONTRACT_DISCOVERY_NOT_VERIFIED";
            return;
        }
        Instant now = clock.instant();
        try {
            var fetched = fetcher.fetch();
            LocalDate today = now.atZone(IST).toLocalDate();
            List<FuturesContract> contracts = UpstoxFuturesInstrumentMaster.parse(
                new ByteArrayInputStream(gunzipIfNeeded(fetched.bytes())), today);
            var index = UpstoxFuturesInstrumentMaster.index(contracts);
            snapshot = new Snapshot(now, fetched.sha256(), contracts.size(), index);
            lastFailure = null;
        } catch (Exception e) {
            // Only stable codes are kept; provider bodies and exception text are not.
            lastFailure = e instanceof UpstoxFuturesInstrumentMaster.MalformedInstrumentMasterException
                ? "INSTRUMENT_MASTER_MALFORMED" : "INSTRUMENT_MASTER_FETCH_FAILED";
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

    private static byte[] gunzipIfNeeded(byte[] bytes) throws java.io.IOException {
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            try (var in = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return in.readAllBytes();
            }
        }
        return bytes;
    }
}
