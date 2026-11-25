package in.aitradingsystem.intelligence;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V63 read-only ADMIN diagnostics for one exact PAPER/SHADOW case.
 * Derived from immutable V56/V59/V60/V61/V62 state, not inferred P&L or
 * independent market-data/model-runtime validation.
 */
@Service
public class ResearchReceiptDiagnosticsService {
    public static final String CONTRACT = "v4-research-receipt-diagnostics.v1";
    public record ModelStatus(
        UUID modelVersionId, String role, String family, String versionFingerprint,
        String artifactFingerprint, String status, String reasonCode,
        UUID reservationId, Instant reservedAt, Instant expiresAt,
        Instant recordedAt, String responseFingerprint, boolean identityBound) {}
    public record Report(
        String contractVersion, UUID sessionId, String caseKey,
        String enrollmentFingerprint, String sessionSafetyState, long sessionSafetyRevision,
        boolean sourceCaptured, boolean featuresFrozen,
        String sourceInputFingerprint, String featurePayloadFingerprint,
        boolean matchedCaseFinalized, String caseFingerprint,
        String receiptRosterFingerprint, int expectedModels,
        int successfulReceipts, int unresolvedModels, List<ModelStatus> models,
        boolean independentlyVerifiedRuntime, boolean sourceRightsVerified,
        boolean paperShadowSoakAccepted, boolean brokerReconciliationAccepted,
        boolean liveExecutionEnabled, boolean canAuthorizeCapital) {}
    private record Session(String fingerprint, String state, long revision) {}
    private record Source(String inputFingerprint, String featureFingerprint) {}
    private record Link(String caseFingerprint, String rosterFingerprint) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public ResearchReceiptDiagnosticsService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public Report inspect(UUID sessionId, String caseKey) {
        if (sessionId == null || caseKey == null
                || !caseKey.matches("^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$")) {
            throw new IllegalArgumentException("Exact session and bounded case key required");
        }
        Session session = jdbc.sql("""
                SELECT s.enrollment_fingerprint, st.state, st.revision
                  FROM v4_matched_session s
                  JOIN v4_matched_session_state st ON st.session_id = s.session_id
                 WHERE s.session_id = :sessionId
                """)
            .param("sessionId", sessionId)
            .query((rs, row) -> new Session(rs.getString(1), rs.getString(2),
                rs.getLong(3)))
            .optional().orElseThrow(() ->
                new NoSuchElementException("Unknown matched session"));

        Source source = jdbc.sql("""
                SELECT sc.input_fingerprint, f.feature_payload_fingerprint
                  FROM v4_matched_source_capture sc
                  LEFT JOIN v4_matched_feature_projection f
                    ON f.session_id = sc.session_id AND f.case_key = sc.case_key
                   AND f.source_capture_fingerprint = sc.capture_fingerprint
                 WHERE sc.session_id = :sessionId AND sc.case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query((rs, row) -> new Source(rs.getString(1), rs.getString(2)))
            .optional().orElse(null);

        Link link = jdbc.sql("""
                SELECT case_fingerprint, receipt_roster_fingerprint
                  FROM v4_research_matched_case_link
                 WHERE session_id = :sessionId AND case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query((rs, row) -> new Link(rs.getString(1), rs.getString(2)))
            .optional().orElse(null);

        Instant now = clock.instant();
        List<ModelStatus> members = jdbc.sql("""
                SELECT p.model_version_id, p.role, md.family,
                       mv.version_fingerprint, mv.artifact_fingerprint,
                       r.reservation_id, r.enrollment_fingerprint,
                       r.model_version_fingerprint,
                       r.artifact_fingerprint AS reserved_artifact,
                       r.source_input_fingerprint, r.feature_payload_fingerprint,
                       r.reserved_at, r.expires_at,
                       rr.status, rr.reason_code, rr.recorded_at,
                       rr.response_fingerprint
                  FROM v4_matched_participant p
                  JOIN model_version mv ON mv.version_id = p.model_version_id
                  JOIN model_definition md ON md.definition_id = mv.definition_id
                  LEFT JOIN v4_research_inference_reservation r
                    ON r.session_id = p.session_id AND r.model_version_id = p.model_version_id
                   AND r.case_key = :caseKey
                  LEFT JOIN v4_research_inference_receipt rr
                    ON rr.reservation_id = r.reservation_id
                 WHERE p.session_id = :sessionId
                 ORDER BY p.model_version_id
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query((rs, row) -> {
                UUID reservationId = rs.getObject("reservation_id", UUID.class);
                Instant expires = timestamp(rs, "expires_at");
                String terminal = rs.getString("status");
                String status = reservationId == null ? "NOT_ATTEMPTED"
                    : terminal == null ? "UNKNOWN_UNRESOLVED"
                    : ("SUCCEEDED".equals(terminal) && expires != null
                        && expires.isBefore(now)) ? "SUCCEEDED_EXPIRED" : terminal;
                String reasonCode = terminal == null
                    ? (reservationId == null ? "NO_RESEARCH_RESERVATION"
                        : "NO_TERMINAL_RECEIPT") : rs.getString("reason_code");
                boolean bound = reservationId != null && source != null
                    && source.featureFingerprint() != null
                    && session.fingerprint().equals(
                        rs.getString("enrollment_fingerprint"))
                    && rs.getString("version_fingerprint").equals(
                        rs.getString("model_version_fingerprint"))
                    && rs.getString("artifact_fingerprint").equals(
                        rs.getString("reserved_artifact"))
                    && source.inputFingerprint().equals(
                        rs.getString("source_input_fingerprint"))
                    && source.featureFingerprint().equals(
                        rs.getString("feature_payload_fingerprint"));
                return new ModelStatus(
                    rs.getObject("model_version_id", UUID.class),
                    rs.getString("role"), rs.getString("family"),
                    rs.getString("version_fingerprint"),
                    rs.getString("artifact_fingerprint"), status, reasonCode,
                    reservationId, timestamp(rs, "reserved_at"), expires,
                    timestamp(rs, "recorded_at"),
                    rs.getString("response_fingerprint"), bound);
            }).list();

        if (members.size() < 2 || members.size() > 10) {
            throw new IllegalStateException("Invalid matched session model roster");
        }
        int successes = 0;
        int unresolved = 0;
        for (var model : members) {
            if ("SUCCEEDED".equals(model.status()) && model.identityBound()) {
                successes++;
            } else {
                unresolved++;
            }
        }
        return new Report(CONTRACT, sessionId, caseKey,
            session.fingerprint(), session.state(), session.revision(),
            source != null, source != null && source.featureFingerprint() != null,
            source == null ? null : source.inputFingerprint(),
            source == null ? null : source.featureFingerprint(),
            link != null, link == null ? null : link.caseFingerprint(),
            link == null ? null : link.rosterFingerprint(), members.size(),
            successes, unresolved, members, false, false, false,
            false, false, false);
    }

    private static Instant timestamp(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        OffsetDateTime date = rs.getObject(column, OffsetDateTime.class);
        return date == null ? null : date.toInstant();
    }
}
