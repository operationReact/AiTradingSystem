package in.aitradingsystem.scanner;

import java.util.Optional;

/** Pure strategy contract for deterministic research scans. Metadata must not change evaluate semantics. */
public interface ScannerStrategy extends StrategyDescriptorProvider {
    String name();

    int minimumCandles();

    @Override
    StrategyDescriptor descriptor();

    Optional<ScanCandidate> evaluate(ScanContext context);
}
