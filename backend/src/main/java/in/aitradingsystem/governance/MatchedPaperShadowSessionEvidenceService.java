package in.aitradingsystem.governance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only, consistent-snapshot aggregation of existing V56 decisions and V57
 * PAPER order/fill evidence. This is not inference, runtime attestation, or P&L.
 */
@Service
public class MatchedPaperShadowSessionEvidenceService {
    public static final String CONTRACT = "v4-matched-paper-shadow-session-evidence.v1";
    private final JdbcClient jdbc;
    private final MatchedPaperShadowIntakeService intake;

    public MatchedPaperShadowSessionEvidenceService(
            JdbcClient jdbc, MatchedPaperShadowIntakeService intake) {
        this.jdbc = jdbc;
        this.intake = intake;
    }

    public record SessionEvidence(
        String contractVersion, UUID sessionId, String enrollmentFingerprint,
        int enrolledModels, long matchedCases, long incompleteModelRosterCases,
        long championEnterCases, long championWaitCases, long championAbstainCases,
        long shadowDisagreementCases, long linkedPaperOrders, long unlinkedChampionEnters,
        long paperFillQuantityMismatchOrders, long recordedFillQuantity,
        BigDecimal recordedPaperFees, BigDecimal recordedPaperSlippage,
        Instant firstDecisionAt, Instant lastDecisionAt, long observedDecisionSpanSeconds,
        List<String> reasonCodes,
        boolean originalLicensedSourceCustodyVerified,
        boolean actualInferenceProducerAttested,
        boolean paperShadowSoakAccepted,
        boolean brokerReconciliationAccepted,
        boolean independentEconomicPerformanceVerified,
        boolean liveOrderExecutionEnabled,
        boolean canAuthorizeCapital) {}

    private record CaseTotals(long count, Instant first, Instant last) {}
    private record ActionTotals(long enter, long wait, long abstain) {}
    private record FillTotals(
        long links, long mismatches, long quantity, BigDecimal fees, BigDecimal slippage) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SessionEvidence read(UUID sessionId) {
        var session = intake.session(sessionId);
        var cases = jdbc.sql("""
                SELECT COUNT(*) AS matched_count,
                       MIN(decision_at) AS first_decision,
                       MAX(decision_at) AS last_decision
                  FROM v4_matched_case WHERE session_id = :sessionId
                """)
            .param("sessionId", sessionId)
            .query((rs, row) -> new CaseTotals(
                rs.getLong("matched_count"),
                instantOrNull(rs.getObject("first_decision", OffsetDateTime.class)),
                instantOrNull(rs.getObject("last_decision", OffsetDateTime.class))))
            .single();

        long incomplete = jdbc.sql("""
                SELECT COUNT(*) FROM v4_matched_case c
                 WHERE c.session_id = :sessionId
                   AND (SELECT COUNT(*) FROM v4_matched_decision d
                         WHERE d.session_id = c.session_id AND d.case_key = c.case_key)
                       <> :modelCount
                """)
            .param("sessionId", sessionId)
            .param("modelCount", session.models().size())
            .query(Long.class)
            .single();

        ActionTotals actions = jdbc.sql("""
                SELECT COUNT(*) FILTER (WHERE d.action = 'ENTER') AS enters,
                       COUNT(*) FILTER (WHERE d.action = 'WAIT') AS waits,
                       COUNT(*) FILTER (WHERE d.action = 'ABSTAIN') AS abstains
                  FROM v4_matched_decision d
                  JOIN v4_matched_participant p
                    ON p.session_id = d.session_id AND p.model_version_id = d.model_version_id
                 WHERE d.session_id = :sessionId AND p.role = 'PAPER_CHAMPION'
                """)
            .param("sessionId", sessionId)
            .query((rs, row) -> new ActionTotals(
                rs.getLong("enters"), rs.getLong("waits"), rs.getLong("abstains")))
            .single();

        long disagreements = jdbc.sql("""
                SELECT COUNT(*) FROM v4_matched_case c
                 WHERE c.session_id = :sessionId
                   AND EXISTS (
                       SELECT 1 FROM v4_matched_decision champion
                       JOIN v4_matched_participant champion_member
                         ON champion_member.session_id = champion.session_id
                        AND champion_member.model_version_id = champion.model_version_id
                       JOIN v4_matched_decision challenger
                         ON challenger.session_id = champion.session_id
                        AND challenger.case_key = champion.case_key
                       JOIN v4_matched_participant challenger_member
                         ON challenger_member.session_id = challenger.session_id
                        AND challenger_member.model_version_id = challenger.model_version_id
                      WHERE champion.session_id = c.session_id
                        AND champion.case_key = c.case_key
                        AND champion_member.role = 'PAPER_CHAMPION'
                        AND challenger_member.role = 'SHADOW_CHALLENGER'
                        AND challenger.action <> champion.action
                   )
                """)
            .param("sessionId", sessionId)
            .query(Long.class)
            .single();

        FillTotals linked = jdbc.sql("""
                SELECT COUNT(*) AS linked_orders,
                       COUNT(*) FILTER (
                         WHERE o.filled_quantity <> fills.quantity
                       ) AS quantity_mismatches,
                       COALESCE(SUM(fills.quantity), 0) AS total_fills,
                       COALESCE(SUM(fills.fees), 0) AS total_fees,
                       COALESCE(SUM(fills.slippage), 0) AS total_slippage
                  FROM v4_matched_paper_order_link l
                  JOIN paper_order o ON o.id = l.paper_order_id
                  LEFT JOIN LATERAL (
                      SELECT COALESCE(SUM(f.quantity), 0) AS quantity,
                             COALESCE(SUM(f.fee_amount), 0) AS fees,
                             COALESCE(SUM(f.slippage_amount), 0) AS slippage
                        FROM paper_fill f WHERE f.order_id = l.paper_order_id
                  ) fills ON TRUE
                 WHERE l.session_id = :sessionId
                """)
            .param("sessionId", sessionId)
            .query((rs, row) -> new FillTotals(
                rs.getLong("linked_orders"),
                rs.getLong("quantity_mismatches"),
                rs.getLong("total_fills"),
                rs.getBigDecimal("total_fees"),
                rs.getBigDecimal("total_slippage")))
            .single();

        var reasons = new ArrayList<String>();
        if (cases.count() == 0) reasons.add("NO_MATCHED_CASES");
        if (incomplete > 0 || actions.enter() + actions.wait() + actions.abstain() != cases.count()) {
            reasons.add("INCOMPLETE_MODEL_DECISION_COHORT");
        }
        if (linked.links() > actions.enter()) reasons.add("PAPER_LINK_COUNT_EXCEEDS_ENTERS");
        if (actions.enter() > linked.links()) reasons.add("UNLINKED_CHAMPION_ENTER_CASES");
        if (linked.mismatches() > 0) reasons.add("PAPER_FILL_QUANTITY_MISMATCH");
        // Deliberately never infer licensing, runtime producer, soak or broker
        // reconciliation acceptance from self-reported source/model records.
        reasons.addAll(List.of(
            "ACTUAL_MODEL_INFERENCE_PRODUCER_NOT_ATTESTED",
            "BROKER_RECONCILIATION_NOT_ACCEPTED",
            "INDEPENDENT_MARKET_AND_NEWS_CUSTODY_NOT_VERIFIED",
            "INDEPENDENT_PAPER_SHADOW_SOAK_NOT_ACCEPTED",
            "OUT_OF_SAMPLE_ECONOMIC_PERFORMANCE_NOT_VERIFIED",
            "LIVE_CAPITAL_AUTHORITY_DISABLED"));
        reasons.sort(String::compareTo);
        long spanSeconds = cases.first() == null ? 0
            : ChronoUnit.SECONDS.between(cases.first(), cases.last());

        return new SessionEvidence(
            CONTRACT, sessionId, session.enrollmentFingerprint(),
            session.models().size(), cases.count(), incomplete,
            actions.enter(), actions.wait(), actions.abstain(), disagreements,
            linked.links(), Math.max(0, actions.enter() - linked.links()),
            linked.mismatches(), linked.quantity(), linked.fees(),
            linked.slippage(), cases.first(), cases.last(), spanSeconds,
            List.copyOf(reasons), false, false, false, false, false, false, false);
    }

    private static Instant instantOrNull(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
