package in.aitradingsystem.marketdata;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A point-in-time exchange session authority, separate from the operational
 * weekday/holiday heuristic. Historical missing calendar evidence must fail
 * closed; weekends, Muhurat days and suspensions cannot be guessed.
 *
 * A future implementation must verify provenance against licensed official
 * historical exchange sessions; no real implementation is installed here.
 */
@FunctionalInterface
public interface GoldenHistoricalSessionEvidence {
    SessionEvidence resolve(String exchange, LocalDate sessionDate);

    enum Status { TRADING, CLOSED, UNKNOWN }

    record SessionEvidence(Status status, Instant sessionClose, String evidenceDigest,
                           Instant verifiedAt) {}
}
