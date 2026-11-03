package in.aitradingsystem.scanner;

import in.aitradingsystem.intelligence.RankedOpportunityV2;
import java.util.List;

/**
 * Read-only source for one frozen ranked-opportunity cross-section.
 *
 * <p>Implementations must not synthesize candidates from legacy research scans. Returning an empty
 * list is the correct fail-closed behavior when no ranked V3 feed is available.</p>
 */
@FunctionalInterface
public interface RankedOpportunityProvider {
    List<RankedOpportunityV2> currentOpportunities();
}
