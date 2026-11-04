package in.aitradingsystem.intelligence;

import in.aitradingsystem.execution.TradingExecutionAuthority;
import in.aitradingsystem.governance.MatchedSessionSafetyService;
import in.aitradingsystem.governance.ModelEmergencyControlService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V61 research-only, append-only reservation + one terminal receipt.
 *
 * Reservation is committed BEFORE the eventual worker call; no transport is
 * performed by this service and it never resubmits unknown/pending requests.
 * An absent terminal receipt is UNKNOWN for recovery purposes, not permission
 * to retry. This is not a model execution controller or a trading authority.
 */
@Service
public class ResearchInferenceAttemptLedger {
    public enum Status { SUCCEEDED, REJECTED, TIMEOUT, UNKNOWN }
    public enum FailureCode {
        RESPONSE_REJECTED, TRANSPORT_TIMEOUT, TRANSPORT_UNKNOWN,
        SESSION_REVOKED, EVIDENCE_EXPIRED, OPERATOR_MARKED_UNKNOWN
    }
    public record Reservation(
        UUID reservationId, UUID sessionId, String caseKey, UUID modelVersionId,
        String enrollmentFingerprint, String modelVersionFingerprint,
        String artifactFingerprint, String sourceInputFingerprint,
        String featurePayloadFingerprint, Instant featureAsOf,
        Instant reservedAt, Instant expiresAt) {}
    public record Receipt(UUID reservationId, Status status, String reasonCode,
        String requestFingerprint, String responseFingerprint, String modelFamily,
        Double probability, String action, Instant evaluatedAt, Instant recordedAt,
        boolean canAuthorizeCapital) {}

    private record FrozenIdentity(
        String enrollmentFingerprint, String modelVersionFingerprint,
        String participantFingerprint, String artifactFingerprint,
        String sourceInputFingerprint, String featurePayloadFingerprint,
        Instant featureAsOf) {}

    private static final String HASH = "^[a-f0-9]{64}$";
    private static final String ACTOR = "java-research-ledger";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final MatchedSessionSafetyService safety;
    private final ModelEmergencyControlService emergency;
    private final TradingExecutionAuthority authority;

    public ResearchInferenceAttemptLedger(JdbcClient jdbc, Clock clock,
            MatchedSessionSafetyService safety,
            ModelEmergencyControlService emergency,
            TradingExecutionAuthority authority) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.safety = safety;
        this.emergency = emergency;
        this.authority = authority;
    }

    /** One and only one reservation per frozen session/case/registered model. */
    @Transactional
    public Reservation reserve(UUID sessionId, String caseKey, UUID modelVersionId) {
        requirePaper();
        if (sessionId == null || modelVersionId == null || caseKey == null
                || !caseKey.matches("^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$")) {
            throw new IllegalArgumentException("Exact session, case and model are required");
        }
        // Holds the session state through the insertion transaction.
        safety.requireActiveForAppend(sessionId);
        emergency.requireModelAllowed(modelVersionId);
        FrozenIdentity frozen = jdbc.sql("""
                SELECT s.enrollment_fingerprint, mv.version_fingerprint,
                       p.model_fingerprint, mv.artifact_fingerprint,
                       sc.input_fingerprint, f.feature_payload_fingerprint, f.feature_as_of
                  FROM v4_matched_session s
                  JOIN v4_matched_participant p
                    ON p.session_id = s.session_id AND p.model_version_id = :modelId
                  JOIN model_version mv ON mv.version_id = p.model_version_id
                  JOIN v4_matched_source_capture sc ON sc.session_id = s.session_id
                  JOIN v4_matched_feature_projection f
                    ON f.session_id = sc.session_id AND f.case_key = sc.case_key
                   AND f.source_capture_fingerprint = sc.capture_fingerprint
                 WHERE s.session_id = :sessionId AND sc.case_key = :caseKey
                   AND NOT EXISTS (
                       SELECT 1 FROM v4_matched_case d
                        WHERE d.session_id = s.session_id AND d.case_key = sc.case_key)
                """)
            .param("modelId", modelVersionId).param("sessionId", sessionId)
            .param("caseKey", caseKey)
            .query((rs, row) -> new FrozenIdentity(
                rs.getString("enrollment_fingerprint"),
                rs.getString("version_fingerprint"),
                rs.getString("model_fingerprint"),
                rs.getString("artifact_fingerprint"),
                rs.getString("input_fingerprint"),
                rs.getString("feature_payload_fingerprint"),
                rs.getObject("feature_as_of", OffsetDateTime.class).toInstant()))
            .optional()
            .orElseThrow(() -> new NoSuchElementException(
                "Unenrolled, unfrozen or already decided case is not eligible"));
        if (!frozen.participantFingerprint().equals(frozen.modelVersionFingerprint())
                || !frozen.enrollmentFingerprint().matches(HASH)
                || !frozen.artifactFingerprint().matches(HASH)
                || !frozen.sourceInputFingerprint().matches(HASH)
                || !frozen.featurePayloadFingerprint().matches(HASH)) {
            throw new IllegalStateException("Frozen model/source identity mismatch");
        }
        Instant now = clock.instant();
        if (frozen.featureAsOf().isAfter(now)) {
            throw new IllegalStateException("Future-dated frozen feature snapshot");
        }
        Reservation reservation = new Reservation(UUID.randomUUID(), sessionId, caseKey,
            modelVersionId, frozen.enrollmentFingerprint(), frozen.modelVersionFingerprint(),
            frozen.artifactFingerprint(), frozen.sourceInputFingerprint(),
            frozen.featurePayloadFingerprint(), frozen.featureAsOf(), now,
            now.plusSeconds(60));
        // A collision is an explicit failure: NEVER replay a previously attempted
        // request even if its response is absent, expired, UNKNOWN or rejected.
        jdbc.sql("""
                INSERT INTO v4_research_inference_reservation (
                    reservation_id, session_id, case_key, model_version_id,
                    enrollment_fingerprint, model_version_fingerprint,
                    artifact_fingerprint, source_input_fingerprint,
                    feature_payload_fingerprint, feature_as_of, reserved_at,
                    expires_at, reserved_by)
                VALUES (:id, :sessionId, :caseKey, :modelId, :enrollment,
                    :modelFingerprint, :artifact, :sourceHash, :featureHash,
                    :asOf, :reserved, :expires, :actor)
                """)
            .param("id", reservation.reservationId())
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .param("modelId", modelVersionId)
            .param("enrollment", reservation.enrollmentFingerprint())
            .param("modelFingerprint", reservation.modelVersionFingerprint())
            .param("artifact", reservation.artifactFingerprint())
            .param("sourceHash", reservation.sourceInputFingerprint())
            .param("featureHash", reservation.featurePayloadFingerprint())
            .param("asOf", utc(reservation.featureAsOf()))
            .param("reserved", utc(now))
            .param("expires", utc(reservation.expiresAt()))
            .param("actor", ACTOR)
            .update();
        return reservation;
    }

    /** Store a client-validated trained response; never append any V56 decision. */
    @Transactional
    public Receipt succeed(UUID reservationId, ResearchArtifactInferenceClient.Result result) {
        requirePaper();
        if (result == null || result.canAuthorizeCapital()) {
            throw new IllegalArgumentException("Only research-only validated inference may succeed");
        }
        Reservation r = reservation(reservationId);
        // A valid Python payload for CatBoost must never be recorded as a
        // LightGBM roster member (or vice versa), even if IDs/hash fields match.
        String[] identity = jdbc.sql("""
                SELECT sc.symbol, md.family, mv.artifact_fingerprint
                  FROM v4_matched_source_capture sc
                  JOIN v4_matched_participant p ON p.session_id = sc.session_id
                  JOIN model_version mv ON mv.version_id = p.model_version_id
                  JOIN model_definition md ON md.definition_id = mv.definition_id
                 WHERE sc.session_id = :sessionId AND sc.case_key = :caseKey
                   AND p.model_version_id = :modelId
                """)
            .param("sessionId", r.sessionId()).param("caseKey", r.caseKey())
            .param("modelId", r.modelVersionId())
            .query((rs, row) -> new String[]{
                rs.getString("symbol"), rs.getString("family"),
                rs.getString("artifact_fingerprint")})
            .single();
        safety.requireActiveForAppend(r.sessionId());
        emergency.requireModelAllowed(r.modelVersionId());
        Instant now = clock.instant();
        if (now.isAfter(r.expiresAt())
                || result.modelVersionId() == null
                || !r.modelVersionId().equals(result.modelVersionId())
                || !r.caseKey().equals(result.caseKey())
                || !identity[0].equals(result.symbol())
                || !identity[1].equals(result.modelFamily())
                || !identity[2].equals(r.artifactFingerprint())
                || !r.sourceInputFingerprint().equals(result.sourceInputFingerprint())
                || !r.artifactFingerprint().equals(result.artifactFingerprint())
                || !validHash(result.requestFingerprint())
                || !validHash(result.responseFingerprint())
                || result.modelFamily() == null
                || !Set.of("CATBOOST", "LIGHTGBM").contains(result.modelFamily())
                || result.action() == null
                || !Set.of("ENTER", "WAIT", "ABSTAIN").contains(result.action())
                || !Double.isFinite(result.probability())
                || result.probability() < 0 || result.probability() > 1
                || result.evaluatedAt() == null
                || result.evaluatedAt().isBefore(r.featureAsOf())
                || result.evaluatedAt().isAfter(now.plusSeconds(5))) {
            throw new IllegalArgumentException("Research receipt mismatch, expired or unsafe");
        }
        jdbc.sql("""
                INSERT INTO v4_research_inference_receipt (
                    reservation_id, status, reason_code, request_fingerprint,
                    response_fingerprint, model_family, probability, action,
                    evaluated_at, recorded_at, recorded_by)
                VALUES (:id, 'SUCCEEDED', 'VALIDATED_RESEARCH_RESPONSE',
                    :requestHash, :responseHash, :family, :probability,
                    :action, :evaluatedAt, :recordedAt, :actor)
                """)
            .param("id", reservationId)
            .param("requestHash", result.requestFingerprint())
            .param("responseHash", result.responseFingerprint())
            .param("family", result.modelFamily())
            .param("probability", result.probability())
            .param("action", result.action())
            .param("evaluatedAt", utc(result.evaluatedAt()))
            .param("recordedAt", utc(now)).param("actor", ACTOR).update();
        return receipt(reservationId);
    }

    /** Failures may be recorded after pause/quarantine: evidence must not vanish. */
    @Transactional
    public Receipt fail(UUID reservationId, Status status, FailureCode reason,
            String requestFingerprint) {
        requirePaper();
        Reservation r = reservation(reservationId);
        if (status == null || reason == null
                || (status == Status.REJECTED && !Set.of(
                    FailureCode.RESPONSE_REJECTED, FailureCode.SESSION_REVOKED,
                    FailureCode.EVIDENCE_EXPIRED).contains(reason))
                || (status == Status.TIMEOUT && reason != FailureCode.TRANSPORT_TIMEOUT)
                || (status == Status.UNKNOWN && !Set.of(
                    FailureCode.TRANSPORT_UNKNOWN,
                    FailureCode.OPERATOR_MARKED_UNKNOWN).contains(reason))
                || status == Status.SUCCEEDED
                || (requestFingerprint != null && !validHash(requestFingerprint))) {
            throw new IllegalArgumentException("Invalid bounded research failure receipt");
        }
        Instant now = clock.instant();
        if (now.isBefore(r.reservedAt())) {
            throw new IllegalStateException("Terminal receipt precedes reservation");
        }
        jdbc.sql("""
                INSERT INTO v4_research_inference_receipt (
                    reservation_id, status, reason_code,
                    request_fingerprint, recorded_at, recorded_by)
                VALUES (:id, :status, :reason, :requestHash, :recordedAt, :actor)
                """)
            .param("id", reservationId).param("status", status.name())
            .param("reason", reason.name()).param("requestHash", requestFingerprint,
                java.sql.Types.CHAR)
            .param("recordedAt", utc(now)).param("actor", ACTOR).update();
        return receipt(reservationId);
    }

    @Transactional(readOnly = true)
    public Reservation reservation(UUID reservationId) {
        if (reservationId == null) throw new IllegalArgumentException("Reservation ID required");
        return jdbc.sql("""
                SELECT * FROM v4_research_inference_reservation
                 WHERE reservation_id = :id
                """)
            .param("id", reservationId)
            .query((rs, row) -> new Reservation(
                rs.getObject("reservation_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getString("case_key"), rs.getObject("model_version_id", UUID.class),
                rs.getString("enrollment_fingerprint"),
                rs.getString("model_version_fingerprint"),
                rs.getString("artifact_fingerprint"),
                rs.getString("source_input_fingerprint"),
                rs.getString("feature_payload_fingerprint"),
                rs.getObject("feature_as_of", OffsetDateTime.class).toInstant(),
                rs.getObject("reserved_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
            .optional().orElseThrow(() -> new NoSuchElementException(
                "Research inference reservation not found"));
    }

    @Transactional(readOnly = true)
    public Receipt receipt(UUID reservationId) {
        if (reservationId == null) throw new IllegalArgumentException("Reservation ID required");
        return jdbc.sql("""
                SELECT * FROM v4_research_inference_receipt WHERE reservation_id = :id
                """)
            .param("id", reservationId)
            .query((rs, row) -> new Receipt(
                rs.getObject("reservation_id", UUID.class),
                Status.valueOf(rs.getString("status")), rs.getString("reason_code"),
                rs.getString("request_fingerprint"), rs.getString("response_fingerprint"),
                rs.getString("model_family"),
                rs.getObject("probability") == null
                    ? null : rs.getBigDecimal("probability").doubleValue(),
                rs.getString("action"),
                rs.getObject("evaluated_at", OffsetDateTime.class) == null ? null
                    : rs.getObject("evaluated_at", OffsetDateTime.class).toInstant(),
                rs.getObject("recorded_at", OffsetDateTime.class).toInstant(), false))
            .optional().orElseThrow(() -> new NoSuchElementException(
                "No terminal receipt; outcome is UNKNOWN and must not be retried"));
    }

    private void requirePaper() {
        if (!TradingExecutionAuthority.PAPER.equals(authority.current().tradingMode())) {
            throw new IllegalStateException("Research inference ledger requires PAPER mode");
        }
    }
    private static boolean validHash(String value) {
        return value != null && value.matches(HASH);
    }
    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
