package in.aitradingsystem.mind;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Research outcome ledger; references immutable original research candidate.
 * The insert-select refuses NO_SIGNAL receipts or changed original fingerprints.
 */
@Repository
public class MindProspectiveOutcomeRepository {
    private final JdbcTemplate jdbc;

    public MindProspectiveOutcomeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Receipt(UUID cohortId, int memberSlot, Instant signalCandleAt,
                          Instant recordedAt, String candidateFingerprint,
                          String originalSourceDigest, BigDecimal referencePrice,
                          BigDecimal stop, BigDecimal target,
                          long instrumentId, UUID catalogueVersion,
                          String exchange, String tradingSymbol, String symbolToken) {}

    public record Totals(long candidates, long evaluated, long netPositive) {}

    public List<Receipt> candidatePage(UUID cohortId, int offset, int limit) {
        if (cohortId == null || offset < 0 || offset > 15000 ||
            limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid bounded candidate page");
        }
        return jdbc.query("""
                SELECT r.cohort_id, r.member_slot, r.signal_candle_at,
                       r.recorded_at, r.candidate_fingerprint,
                       r.source_digest, r.reference_price, r.suggested_stop,
                       r.suggested_target, m.instrument_id, m.catalogue_version,
                       m.exchange, m.trading_symbol, m.symbol_token
                  FROM mind_prospective_signal_receipt r
                  JOIN mind_prospective_member m
                    ON m.cohort_id = r.cohort_id AND m.slot = r.member_slot
                 WHERE r.cohort_id = ? AND r.status = 'RESEARCH_CANDIDATE'
                 ORDER BY r.signal_candle_at, r.member_slot
                 LIMIT ? OFFSET ?
                """, (rs, n) -> new Receipt(
                    rs.getObject("cohort_id", UUID.class),
                    rs.getInt("member_slot"),
                    instant(rs.getObject("signal_candle_at", OffsetDateTime.class)),
                    instant(rs.getObject("recorded_at", OffsetDateTime.class)),
                    rs.getString("candidate_fingerprint"),
                    rs.getString("source_digest"),
                    rs.getBigDecimal("reference_price"),
                    rs.getBigDecimal("suggested_stop"),
                    rs.getBigDecimal("suggested_target"),
                    rs.getLong("instrument_id"),
                    rs.getObject("catalogue_version", UUID.class),
                    rs.getString("exchange"),
                    rs.getString("trading_symbol"),
                    rs.getString("symbol_token")), cohortId, limit, offset);
    }

    public boolean alreadyRecorded(Receipt receipt) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM mind_prospective_outcome
                 WHERE cohort_id = ? AND member_slot = ?
                   AND signal_candle_at = ?
                """, Long.class, receipt.cohortId(), receipt.memberSlot(),
                utc(receipt.signalCandleAt()));
        return count != null && count > 0;
    }

    public boolean append(Receipt receipt, MindProspectiveOutcomeEvaluator.Outcome outcome,
                          Instant evaluatedAt) {
        if (receipt == null || outcome == null || evaluatedAt == null ||
            outcome.outcomeSourceDigest() == null ||
            !outcome.outcomeSourceDigest().matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid outcome record");
        }
        int saved = jdbc.update("""
                INSERT INTO mind_prospective_outcome
                  (cohort_id, member_slot, signal_candle_at,
                   entry_candle_at, exit_candle_at, evaluated_at,
                   candidate_fingerprint, signal_source_digest, outcome_source_digest,
                   raw_entry, raw_exit, gross_return_percent,
                   illustrative_net_return_percent, exit_reason, source_bar_count)
                SELECT r.cohort_id, r.member_slot, r.signal_candle_at,
                       ?, ?, ?, r.candidate_fingerprint, r.source_digest,
                       ?, ?, ?, ?, ?, ?, ?
                  FROM mind_prospective_signal_receipt r
                 WHERE r.cohort_id = ? AND r.member_slot = ?
                   AND r.signal_candle_at = ?
                   AND r.status = 'RESEARCH_CANDIDATE'
                   AND r.candidate_fingerprint = ?
                   AND r.source_digest = ?
                ON CONFLICT (cohort_id, member_slot, signal_candle_at) DO NOTHING
                """, utc(outcome.entryCandleAt()), utc(outcome.exitCandleAt()),
                utc(evaluatedAt), outcome.outcomeSourceDigest(),
                outcome.rawEntry(), outcome.rawExit(), outcome.grossReturnPercent(),
                outcome.illustrativeNetReturnPercent(), outcome.exitReason(),
                outcome.sourceBars(), receipt.cohortId(), receipt.memberSlot(),
                utc(receipt.signalCandleAt()), receipt.candidateFingerprint(),
                receipt.originalSourceDigest());
        return saved == 1;
    }

    public Totals totals(UUID cohortId) {
        Long candidates = jdbc.queryForObject("""
                SELECT count(*) FROM mind_prospective_signal_receipt
                 WHERE cohort_id = ? AND status = 'RESEARCH_CANDIDATE'
                """, Long.class, cohortId);
        return jdbc.query("""
                SELECT count(*) AS evaluated,
                       count(*) FILTER (WHERE illustrative_net_return_percent > 0) AS positives
                  FROM mind_prospective_outcome WHERE cohort_id = ?
                """, rs -> rs.next() ? new Totals(
                candidates == null ? 0 : candidates,
                rs.getLong("evaluated"), rs.getLong("positives"))
                : new Totals(candidates == null ? 0 : candidates, 0, 0),
                cohortId);
    }

    /**
     * Audit reads the original immutable V76 evidence only. It never edits
     * or recomputes previously recorded performance figures.
     */
    public record AuditedOutcome(Instant entryCandleAt, Instant exitCandleAt,
                                 Instant evaluatedAt, String candidateFingerprint,
                                 String signalSourceDigest, String outcomeSourceDigest,
                                 BigDecimal rawEntry, BigDecimal rawExit,
                                 BigDecimal grossReturnPercent,
                                 BigDecimal illustrativeNetReturnPercent,
                                 String exitReason, int sourceBars) {}

    public java.util.Optional<AuditedOutcome> recordedOutcome(Receipt receipt) {
        if (receipt == null || receipt.cohortId() == null ||
            receipt.signalCandleAt() == null) {
            throw new IllegalArgumentException("Invalid outcome audit lookup");
        }
        return jdbc.query("""
                SELECT entry_candle_at, exit_candle_at, evaluated_at,
                       candidate_fingerprint, signal_source_digest,
                       outcome_source_digest, raw_entry, raw_exit,
                       gross_return_percent, illustrative_net_return_percent,
                       exit_reason, source_bar_count
                  FROM mind_prospective_outcome
                 WHERE cohort_id = ? AND member_slot = ?
                   AND signal_candle_at = ?
                """, (rs, n) -> new AuditedOutcome(
                    instant(rs.getObject("entry_candle_at", OffsetDateTime.class)),
                    instant(rs.getObject("exit_candle_at", OffsetDateTime.class)),
                    instant(rs.getObject("evaluated_at", OffsetDateTime.class)),
                    rs.getString("candidate_fingerprint"),
                    rs.getString("signal_source_digest"),
                    rs.getString("outcome_source_digest"),
                    rs.getBigDecimal("raw_entry"), rs.getBigDecimal("raw_exit"),
                    rs.getBigDecimal("gross_return_percent"),
                    rs.getBigDecimal("illustrative_net_return_percent"),
                    rs.getString("exit_reason"), rs.getInt("source_bar_count")),
                receipt.cohortId(), receipt.memberSlot(), utc(receipt.signalCandleAt()))
                .stream().findFirst();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
