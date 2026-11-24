package in.aitradingsystem.news.feature;

import in.aitradingsystem.provider.upstox.UpstoxFnoInstrumentUniverse;
import in.aitradingsystem.tradingrooms.FnoFuturesContractRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves a verified equity or index base symbol against the Upstox F&amp;O universe that the
 * existing {@link FnoFuturesContractRegistry} has loaded.
 *
 * <p>Definition: a symbol is LISTED_FNO only when the loaded universe carries at least one FUT
 * contract whose underlying symbol matches. Options-only underlyings are NOT_LISTED. Any absent,
 * gated, failed or stale catalogue is UNKNOWN_CATALOG, and the source string records the registry
 * state. Nothing here infers CE or PE direction from news, and nothing here changes order or rights
 * behaviour. Read-only.
 */
@Component
public class FnoUnderlyingCatalogueAdapter {
    /** Supplies the loaded snapshot, or empty. */
    interface SnapshotSource {
        Optional<FnoFuturesContractRegistry.UniverseSnapshot> loadedSnapshot();
    }

    private final SnapshotSource snapshots;
    private final Supplier<String> stateSupplier;

    @Autowired
    public FnoUnderlyingCatalogueAdapter(FnoFuturesContractRegistry registry) {
        this.snapshots = registry::loadedUniverseSnapshot;
        this.stateSupplier = () -> registry.universeStatus().state();
    }

    FnoUnderlyingCatalogueAdapter(SnapshotSource snapshots, Supplier<String> stateSupplier) {
        this.snapshots = snapshots;
        this.stateSupplier = stateSupplier;
    }

    public FnoUnderlyingPolicy.Resolution resolve(String baseSymbol) {
        return resolveAsOf(baseSymbol, Instant.now());
    }

    /** Historical research cannot use a catalogue first fetched after the decision. */
    public FnoUnderlyingPolicy.Resolution resolveAsOf(String baseSymbol, Instant asOf) {
        if (asOf == null || asOf.isAfter(Instant.now())) {
            throw new IllegalArgumentException("A non-future F&O research cutoff is required");
        }
        Optional<FnoFuturesContractRegistry.UniverseSnapshot> loaded = snapshots.loadedSnapshot();
        if (loaded.isEmpty()) {
            return new FnoUnderlyingPolicy.Resolution(FnoUnderlyingPolicy.Status.UNKNOWN_CATALOG, null,
                "upstox-fno-universe state=" + stateSupplier.get());
        }
        FnoFuturesContractRegistry.UniverseSnapshot s = loaded.get();
        if (s.fetchedAt().isAfter(asOf) || asOf.isAfter(s.fetchedAt().plus(Duration.ofHours(36)))) {
            return new FnoUnderlyingPolicy.Resolution(FnoUnderlyingPolicy.Status.UNKNOWN_CATALOG, null,
                "upstox-fno-universe state=NO_CAUSAL_CATALOGUE asOf=" + asOf
                    + " fetchedAt=" + s.fetchedAt());
        }
        Set<String> futuresUnderlyings = s.catalog().contracts().stream()
            .filter(c -> "FUT".equals(c.type()))
            .map(UpstoxFnoInstrumentUniverse.Contract::underlyingSymbol)
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
        return FnoUnderlyingPolicy.resolve(baseSymbol, futuresUnderlyings, true,
            "upstox-fno-universe sha256=" + s.sha256() + " fetchedAt=" + s.fetchedAt());
    }
}
