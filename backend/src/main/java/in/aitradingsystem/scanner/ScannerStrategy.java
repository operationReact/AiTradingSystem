package in.aitradingsystem.scanner;

import java.util.Map;
import java.util.Optional;

/** Pure strategy contract for deterministic research scans. Metadata must not change evaluate semantics. */
public interface ScannerStrategy extends StrategyDescriptorProvider {
    String name();

    int minimumCandles();

    @Override
    StrategyDescriptor descriptor();

    /**
     * Typed allow-list for persisted configuration. Empty means this strategy currently has no externalized knobs.
     * Unknown keys and container/script values are rejected by StrategyConfigService.
     */
    default Map<String, StrategyConfigValueType> configurationSchema() {
        return Map.of();
    }

    Optional<ScanCandidate> evaluate(ScanContext context);
}
