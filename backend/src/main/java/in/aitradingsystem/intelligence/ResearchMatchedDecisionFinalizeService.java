package in.aitradingsystem.intelligence;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.execution.TradingExecutionAuthority;
import in.aitradingsystem.governance.MatchedPaperShadowIntakeService;
import in.aitradingsystem.governance.MatchedPaperShadowIntakeService.Action;
import in.aitradingsystem.governance.MatchedPaperShadowIntakeService.Candidate;
import in.aitradingsystem.governance.MatchedPaperShadowIntakeService.ObservationInput;
import in.aitradingsystem.governance.MatchedSessionSafetyService;
import in.aitradingsystem.governance.ModelEmergencyControlService;
import in.aitradingsystem.governance.ModelRegistryService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V62: reconcile a *complete* immutable V61 scored roster into one ORIGINAL V56
 * matched PAPER/SHADOW decision, atomically with an immutable custody link.
 *
 * Does no scoring, no broker calls, no model enable/promotion and no live trades.
 * Never turns unresolved, timed-out, rejected or stale worker receipts into a
 * synthetic prediction. The original V56 validator remains authoritative.
 */
@Service
public class ResearchMatchedDecisionFinalizeService {
    public static final String CONTRACT = "v4-research-matched-receipt-finalization.v1";
    private static final String HASH = "^[a-f0-9]{64}$";
    public record FinalizedView(
        String contractVersion, UUID sessionId, String caseKey,
        String caseFingerprint, String receiptRosterFingerprint, int modelCount,
        boolean independentlyVerifiedRuntime, boolean paperOrderSubmitted,
        boolean modelPromotionAllowed, boolean canAuthorizeCapital) {}

    private record Source(
        String enrollmentFingerprint, String symbol, Instant capturedAt,
        Instant marketAt, Instant newsAt, String marketHash, String newsHash,
        String inputHash, String captureHash, String frozenCaptureHash,
        String featureHash, Instant featureAsOf, Instant projectedAt) {}

    private record ModelReceipt(
        UUID modelVersionId, String role, String participantFingerprint,
        String versionFingerprint, String registeredArtifact, String family,
        UUID reservationId, String reservedEnrollmentFingerprint,
        String reservedVersionFingerprint, String reservedArtifact,
        String reservedSourceHash, String reservedFeatureHash,
        Instant reservedFeatureAsOf, Instant reservedAt, Instant expiresAt,
        String status, String reason, String requestFingerprint,
        String responseFingerprint, String receivedFamily,
        String action, Instant evaluatedAt, Instant receiptRecordedAt) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AuditService audit;
    private final TradingExecutionAuthority authority;
    private final MatchedSessionSafetyService safety;
    private final ModelEmergencyControlService emergency;
    private final MatchedPaperShadowIntakeService intake;

    public ResearchMatchedDecisionFinalizeService(
            JdbcClient jdbc, Clock clock, AuditService audit,
            TradingExecutionAuthority authority,
            MatchedSessionSafetyService safety,
            ModelEmergencyControlService emergency,
            MatchedPaperShadowIntakeService intake) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.authority = authority;
        this.safety = safety;
        this.emergency = emergency;
        this.intake = intake;
    }

    /**
     * One short PostgreSQL transaction only. Never call the external model
     * worker from here. V58 shared lock prevents races with PAUSE/QUARANTINE.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public FinalizedView finalizeCase(UUID sessionId, String caseKey) {
        if (sessionId == null || caseKey == null
                || !caseKey.matches("^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$")) {
            throw new IllegalArgumentException("Exact registered session/case required");
        }
        if (!TradingExecutionAuthority.PAPER.equals(authority.current().tradingMode())) {
            throw new IllegalStateException("Model receipt finalization requires PAPER mode");
        }
        safety.requireActiveForAppend(sessionId);
        var enrollment = intake.session(sessionId);
        Source source = jdbc.sql("""
                SELECT s.enrollment_fingerprint, sc.symbol, sc.captured_at,
                       sc.market_available_at, sc.news_available_at,
                       sc.market_snapshot_fingerprint, sc.news_snapshot_fingerprint,
                       sc.input_fingerprint, sc.capture_fingerprint,
                       f.source_capture_fingerprint AS frozen_capture_hash,
                       f.feature_payload_fingerprint, f.feature_as_of, f.projected_at
                  FROM v4_matched_session s
                  JOIN v4_matched_source_capture sc ON sc.session_id = s.session_id
                  JOIN v4_matched_feature_projection f
                    ON f.session_id = sc.session_id AND f.case_key = sc.case_key
                 WHERE s.session_id = :sessionId AND sc.case_key = :caseKey
                 FOR SHARE OF s, sc, f
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query((rs, n) -> new Source(
                rs.getString("enrollment_fingerprint"), rs.getString("symbol"),
                time(rs, "captured_at"),
                time(rs, "market_available_at"), time(rs, "news_available_at"),
                rs.getString("market_snapshot_fingerprint"),
                rs.getString("news_snapshot_fingerprint"),
                rs.getString("input_fingerprint"),
                rs.getString("capture_fingerprint"),
                rs.getString("frozen_capture_hash"),
                rs.getString("feature_payload_fingerprint"),
                time(rs, "feature_as_of"), time(rs, "projected_at")))
            .optional()
            .orElseThrow(() -> new NoSuchElementException(
                "Frozen source/features missing for roster finalization"));
        if (!enrollment.enrollmentFingerprint().equals(source.enrollmentFingerprint())
                || !source.captureHash().equals(source.frozenCaptureHash())
                || !hash(source.inputHash()) || !hash(source.featureHash())) {
            throw new IllegalStateException("Frozen source identity or enrollment changed");
        }
        List<ModelReceipt> receipts = jdbc.sql("""
                SELECT p.model_version_id, p.role, p.model_fingerprint,
                       mv.version_fingerprint, mv.artifact_fingerprint AS registered_artifact,
                       md.family,
                       r.reservation_id, r.enrollment_fingerprint,
                       r.model_version_fingerprint, r.artifact_fingerprint AS reserved_artifact,
                       r.source_input_fingerprint AS reserved_source_hash,
                       r.feature_payload_fingerprint AS reserved_feature_hash,
                       r.feature_as_of AS reserved_feature_as_of,
                       r.reserved_at, r.expires_at,
                       rr.status, rr.reason_code, rr.request_fingerprint,
                       rr.response_fingerprint, rr.model_family, rr.action,
                       rr.evaluated_at, rr.recorded_at AS receipt_recorded_at
                  FROM v4_matched_participant p
                  JOIN model_version mv ON mv.version_id = p.model_version_id
                  JOIN model_definition md ON md.definition_id = mv.definition_id
                  LEFT JOIN v4_research_inference_reservation r
                    ON r.session_id = p.session_id AND r.case_key = :caseKey
                   AND r.model_version_id = p.model_version_id
                  LEFT JOIN v4_research_inference_receipt rr
                    ON rr.reservation_id = r.reservation_id
                 WHERE p.session_id = :sessionId
                 ORDER BY p.model_version_id
                """)
            .param("caseKey", caseKey).param("sessionId", sessionId)
            .query((rs, n) -> new ModelReceipt(
                rs.getObject("model_version_id", UUID.class),
                rs.getString("role"), rs.getString("model_fingerprint"),
                rs.getString("version_fingerprint"), rs.getString("registered_artifact"),
                rs.getString("family"), rs.getObject("reservation_id", UUID.class),
                rs.getString("enrollment_fingerprint"),
                rs.getString("model_version_fingerprint"),
                rs.getString("reserved_artifact"), rs.getString("reserved_source_hash"),
                rs.getString("reserved_feature_hash"),
                timeOrNull(rs, "reserved_feature_as_of"),
                timeOrNull(rs, "reserved_at"), timeOrNull(rs, "expires_at"),
                rs.getString("status"), rs.getString("reason_code"),
                rs.getString("request_fingerprint"), rs.getString("response_fingerprint"),
                rs.getString("model_family"), rs.getString("action"),
                timeOrNull(rs, "evaluated_at"), timeOrNull(rs, "receipt_recorded_at")))
            .list();
        if (receipts.size() < 2 || receipts.size() > 10
                || receipts.size() != enrollment.models().size()) {
            throw new IllegalStateException("Incomplete or invalid enrolled model roster");
        }
        Instant now = clock.instant();
        List<Candidate> candidates = new ArrayList<>();
        List<String> fingerprintParts = new ArrayList<>(List.of(
            CONTRACT, sessionId.toString(), caseKey,
            enrollment.enrollmentFingerprint(), source.inputHash(), source.featureHash()));
        Instant decisionAt = source.capturedAt();
        Set<UUID> uniqueModels = new HashSet<>();
        int championCount = 0;
        for (ModelReceipt receipt : receipts) {
            if (receipt.modelVersionId() == null || !uniqueModels.add(receipt.modelVersionId())
                    || receipt.role() == null || !Set.of(
                        "PAPER_CHAMPION", "SHADOW_CHALLENGER").contains(receipt.role())) {
                throw new IllegalStateException("Unsafe model roster");
            }
            if ("PAPER_CHAMPION".equals(receipt.role())) championCount++;
            emergency.requireModelAllowed(receipt.modelVersionId());
            var member = enrollment.models().stream()
                .filter(p -> p.modelVersionId().equals(receipt.modelVersionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unenrolled model receipt"));
            if (!member.role().name().equals(receipt.role())
                    || !member.modelFingerprint().equals(receipt.participantFingerprint())
                    || !receipt.versionFingerprint().equals(receipt.participantFingerprint())
                    || receipt.reservationId() == null
                    || !enrollment.enrollmentFingerprint().equals(
                        receipt.reservedEnrollmentFingerprint())
                    || !receipt.versionFingerprint().equals(
                        receipt.reservedVersionFingerprint())
                    || !receipt.registeredArtifact().equals(receipt.reservedArtifact())
                    || !source.inputHash().equals(receipt.reservedSourceHash())
                    || !source.featureHash().equals(receipt.reservedFeatureHash())
                    || !source.featureAsOf().equals(receipt.reservedFeatureAsOf())
                    || receipt.reservedAt() == null || receipt.expiresAt() == null
                    || receipt.reservedAt().isBefore(source.projectedAt())
                    || !receipt.expiresAt().isAfter(receipt.reservedAt())
                    || receipt.expiresAt().isBefore(now)
                    || !"SUCCEEDED".equals(receipt.status())
                    || !"VALIDATED_RESEARCH_RESPONSE".equals(receipt.reason())
                    || !hash(receipt.requestFingerprint()) || !hash(receipt.responseFingerprint())
                    || !Set.of("CATBOOST", "LIGHTGBM").contains(receipt.family())
                    || !receipt.family().equals(receipt.receivedFamily())
                    || receipt.evaluatedAt() == null || receipt.receiptRecordedAt() == null
                    || receipt.evaluatedAt().isBefore(source.featureAsOf())
                    || receipt.evaluatedAt().isBefore(receipt.reservedAt())
                    || receipt.evaluatedAt().isAfter(now)
                    || receipt.receiptRecordedAt().isBefore(receipt.evaluatedAt())
                    || receipt.receiptRecordedAt().isAfter(now)
                    || receipt.receiptRecordedAt().isAfter(receipt.expiresAt())
                    || receipt.action() == null
                    || !Set.of("ENTER", "WAIT", "ABSTAIN").contains(receipt.action())) {
                throw new IllegalStateException(
                    "Missing, stale, unverified or unsafe research inference receipt");
            }
            decisionAt = receipt.evaluatedAt().isAfter(decisionAt)
                ? receipt.evaluatedAt() : decisionAt;
            candidates.add(new Candidate(receipt.modelVersionId(),
                receipt.evaluatedAt(), Action.valueOf(receipt.action()),
                source.inputHash()));
            fingerprintParts.add(receipt.modelVersionId().toString());
            fingerprintParts.add(receipt.reservationId().toString());
            fingerprintParts.add(receipt.requestFingerprint());
            fingerprintParts.add(receipt.responseFingerprint());
            fingerprintParts.add(receipt.action());
            fingerprintParts.add(receipt.evaluatedAt().toString());
        }
        if (championCount != 1) {
            throw new IllegalStateException("No uniquely enrolled PAPER champion");
        }
        // The original V56 service independently revalidates source chronology,
        // exact roster, candidate fingerprint lineage and risk mode. Both
        // writes share this transaction; a V62 failure rolls V56 back.
        var observation = intake.append(sessionId, new ObservationInput(
            caseKey, source.symbol(), decisionAt, source.marketAt(), source.newsAt(),
            source.marketHash(), source.newsHash(), source.inputHash(), candidates));
        String rosterHash = ModelRegistryService.fingerprint(
            fingerprintParts.toArray(String[]::new));
        var previous = jdbc.sql("""
                SELECT case_fingerprint, receipt_roster_fingerprint
                  FROM v4_research_matched_case_link
                 WHERE session_id = :sessionId AND case_key = :caseKey
                """).param("sessionId", sessionId).param("caseKey", caseKey)
            .query((rs, n) -> new String[]{
                rs.getString("case_fingerprint"),
                rs.getString("receipt_roster_fingerprint")})
            .optional();
        if (previous.isPresent()) {
            String[] prior = previous.get();
            if (!prior[0].equals(observation.caseFingerprint())
                    || !prior[1].equals(rosterHash)) {
                throw new IllegalStateException("Immutable model receipt roster conflicts");
            }
        } else {
            jdbc.sql("""
                    INSERT INTO v4_research_matched_case_link (
                        session_id, case_key, case_fingerprint,
                        enrollment_fingerprint, source_input_fingerprint,
                        feature_payload_fingerprint, receipt_roster_fingerprint,
                        model_count, recorded_at, recorded_by)
                    VALUES (:sessionId, :caseKey, :caseHash, :enrollment,
                        :sourceHash, :featureHash, :rosterHash,
                        :modelCount, :recordedAt, :actor)
                    """)
                .param("sessionId", sessionId).param("caseKey", caseKey)
                .param("caseHash", observation.caseFingerprint())
                .param("enrollment", enrollment.enrollmentFingerprint())
                .param("sourceHash", source.inputHash())
                .param("featureHash", source.featureHash())
                .param("rosterHash", rosterHash)
                .param("modelCount", receipts.size()).param("recordedAt", at(now))
                .param("actor", currentActor()).update();
            audit.record("V4_RESEARCH_ROSTER_MATCHED_CASE_FINALIZED",
                "V4_PAPER_SHADOW_CASE", sessionId + ":" + caseKey,
                Map.of("sessionId", sessionId.toString(),
                    "caseFingerprint", observation.caseFingerprint(),
                    "receiptRosterFingerprint", rosterHash,
                    "modelCount", receipts.size(),
                    "canAuthorizeCapital", false));
        }
        return new FinalizedView(CONTRACT, sessionId, caseKey,
            observation.caseFingerprint(), rosterHash, receipts.size(),
            false, false, false, false);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
    private static Instant time(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
    private static Instant timeOrNull(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    private static boolean hash(String value) {
        return value != null && value.matches(HASH);
    }
    private static String currentActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken
                || auth.getName() == null || auth.getName().isBlank()) {
            return "java-research-finalizer";
        }
        String name = auth.getName();
        return name.length() > 120 ? name.substring(0, 120) : name;
    }
}
