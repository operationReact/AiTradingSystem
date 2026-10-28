package in.aitradingsystem.scanner;

import java.util.Optional;

/** Pure strategy contract for deterministic research scans. */
public interface ScannerStrategy {
    String name();

    int minimumCandles();

    Optional<ScanCandidate> evaluate(ScanContext context);
}
