package in.aitradingsystem.mind;

import in.aitradingsystem.scanner.ScanCandidate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Database-only immutable signal/no-signal receipts. Never writes orders. */
@Repository
public class MindProspectiveSignalRepository {
    private final JdbcTemplate jdbc;

    public MindProspectiveSignalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean record(UUID cohortId, int slot, Instant candleAt, Instant at,
                          MindSourceSnapshot frozen, ScanCandidate candidate,
                          String fingerprint) {
        if (cohortId == null || slot < 1 || slot > 20 || candleAt == null ||
            at == null || frozen == null || !candleAt.equals(frozen.lastCandleAt()) ||
            (candidate != null && (fingerprint == null ||
                !fingerprint.matches("[a-f0-9]{64}"))) ||
            (candidate == null && fingerprint != null)) {
            throw new IllegalArgumentException("Invalid prospective signal receipt");
        }
        var closed = candleAt.plusSeconds(300);
        if (at.isBefore(closed) || !at.isBefore(closed.plusSeconds(600))) {
            throw new IllegalArgumentException("Prospective receipt is outside its recording window");
        }
        int inserted = jdbc.update("""
                INSERT INTO mind_prospective_signal_receipt
                    (cohort_id, member_slot, signal_candle_at, signal_bar_closed_at,
                     recorded_at, source_digest, strategy_code, strategy_version,
                     status, candidate_fingerprint, reference_price,
                     suggested_stop, suggested_target)
                VALUES (?, ?, ?, ?, ?, ?, 'MA_RECLAIM_V1', '1',
                        ?, ?, ?, ?, ?)
                ON CONFLICT(cohort_id, member_slot, signal_candle_at) DO NOTHING
                """, cohortId, slot, utc(candleAt), utc(closed), utc(at),
                frozen.digest(), candidate == null ? "NO_SIGNAL" : "RESEARCH_CANDIDATE",
                fingerprint, candidate == null ? null : candidate.referencePrice(),
                candidate == null ? null : candidate.suggestedStop(),
                candidate == null ? null : candidate.suggestedTarget());
        return inserted == 1;
    }

    public record ReceiptCount(int memberSlot, int observations, int signals) {}

    public List<ReceiptCount> counts(UUID cohortId) {
        return jdbc.query("""
                SELECT member_slot,
                       count(*)::integer AS observations,
                       count(*) FILTER(WHERE status = 'RESEARCH_CANDIDATE')::integer AS signals
                  FROM mind_prospective_signal_receipt
                 WHERE cohort_id = ?
                 GROUP BY member_slot ORDER BY member_slot
                """, (rs, n) -> new ReceiptCount(
                rs.getInt("member_slot"), rs.getInt("observations"),
                rs.getInt("signals")), cohortId);
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
