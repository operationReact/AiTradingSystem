package in.aitradingsystem.governance;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.execution.TradingExecutionAuthority;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only, server-timestamped intake of same-case PAPER/SHADOW model decisions.
 * No model inference, hypothetical labels, fills, broker calls or capital authority.
 */
@Service
public class MatchedPaperShadowIntakeService {
    public static final String CONTRACT = "v4-matched-runtime-intake.v1";
    private static final String HASH = "^[a-f0-9]{64}$";
    private static final String CASE_KEY = "^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$";

    public enum Role { PAPER_CHAMPION, SHADOW_CHALLENGER }
    public enum Action { ENTER, WAIT, ABSTAIN }

    public record EnrollmentModel(UUID modelVersionId, Role role) {}
    public record EnrollInput(UUID sessionId, String targetSpecFingerprint, List<EnrollmentModel> models) {}
    public record Participant(UUID modelVersionId, Role role, String modelFingerprint) {}
    public record SessionView(
        String contractVersion, UUID sessionId, String targetSpecFingerprint,
        String enrollmentFingerprint, Instant createdAt, String createdBy,
        List<Participant> models, boolean canAuthorizeCapital) {}
    public record Candidate(UUID modelVersionId, Instant predictedAt, Action action, String inputFingerprint) {}
    public record ObservationInput(
        String caseKey, String symbol, Instant decisionAt,
        Instant marketAvailableAt, Instant newsAvailableAt,
        String marketSourceFingerprint, String newsSourceFingerprint, String inputFingerprint,
        List<Candidate> decisions) {}
    public record DecisionView(
        UUID modelVersionId, Role role, Instant predictedAt, Action action, String decisionFingerprint) {}
    public record ObservationView(
        String contractVersion, UUID sessionId, String caseKey, String symbol,
        Instant decisionAt, Instant marketAvailableAt, Instant newsAvailableAt,
        String marketSourceFingerprint, String newsSourceFingerprint, String inputFingerprint,
        String caseFingerprint, Instant recordedAt, List<DecisionView> decisions,
        boolean independentlyVerifiedRuntime, boolean paperOrdersSubmitted,
        boolean canAuthorizeCapital) {}

    private record FrozenSource(
        String symbol, Instant capturedAt, Instant marketAt, Instant newsAt,
        String marketHash, String newsHash, String inputHash) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;
    private final ModelEmergencyControlService emergency;
    private final TradingExecutionAuthority authority;
    private final MatchedSessionSafetyService safety;

    public MatchedPaperShadowIntakeService(
            JdbcClient jdbc, AuditService audit, Clock clock,
            ModelEmergencyControlService emergency, TradingExecutionAuthority authority,
            MatchedSessionSafetyService safety) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.emergency = emergency;
        this.authority = authority;
        this.safety = safety;
    }

    @Transactional
    public SessionView enroll(EnrollInput input) {
        requirePaper();
        if (input == null || input.sessionId() == null) {
            throw new IllegalArgumentException("sessionId is required");
        }
        requireHash(input.targetSpecFingerprint(), "targetSpecFingerprint");
        if (input.models() == null || input.models().size() < 2 || input.models().size() > 10) {
            throw new IllegalArgumentException("two to ten enrolled model versions are required");
        }
        List<EnrollmentModel> members = input.models().stream()
            .sorted(Comparator.comparing(model -> String.valueOf(model.modelVersionId())))
            .toList();
        if (members.stream().anyMatch(model -> model.modelVersionId() == null || model.role() == null)
                || members.stream().map(EnrollmentModel::modelVersionId).distinct().count() != members.size()
                || members.stream().filter(model -> model.role() == Role.PAPER_CHAMPION).count() != 1) {
            throw new IllegalArgumentException("unique versions and exactly one PAPER champion are required");
        }
        List<Participant> registered = new ArrayList<>();
        for (EnrollmentModel member : members) {
            emergency.requireModelAllowed(member.modelVersionId());
            String versionFingerprint = jdbc.sql("""
                    SELECT version_fingerprint FROM model_version WHERE version_id = :id
                    """)
                .param("id", member.modelVersionId())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new NoSuchElementException("Model version not registered"));
            registered.add(new Participant(member.modelVersionId(), member.role(), versionFingerprint));
        }

        List<String> pieces = new ArrayList<>(List.of(CONTRACT, input.targetSpecFingerprint()));
        for (Participant part : registered) {
            pieces.add(part.modelVersionId().toString());
            pieces.add(part.role().name());
            pieces.add(part.modelFingerprint());
        }
        String enrollmentFingerprint = ModelRegistryService.fingerprint(pieces.toArray(String[]::new));
        if (existsSession(input.sessionId())) {
            SessionView existing = session(input.sessionId());
            if (!existing.enrollmentFingerprint().equals(enrollmentFingerprint)) {
                throw new IllegalStateException("Session replay conflicts with frozen enrollment");
            }
            return existing;
        }

        Instant now = clock.instant();
        String actor = actor();
        jdbc.sql("""
                INSERT INTO v4_matched_session (
                    session_id, target_spec_fingerprint, enrollment_fingerprint, created_at, created_by)
                VALUES (:id, :target, :fingerprint, :createdAt, :actor)
                """)
            .param("id", input.sessionId())
            .param("target", input.targetSpecFingerprint())
            .param("fingerprint", enrollmentFingerprint)
            .param("createdAt", at(now))
            .param("actor", actor)
            .update();
        for (Participant part : registered) {
            jdbc.sql("""
                    INSERT INTO v4_matched_participant (
                        session_id, model_version_id, role, model_fingerprint)
                    VALUES (:id, :modelId, :role, :fingerprint)
                    """)
                .param("id", input.sessionId())
                .param("modelId", part.modelVersionId())
                .param("role", part.role().name())
                .param("fingerprint", part.modelFingerprint())
                .update();
        }
        safety.registerEnrollment(input.sessionId(), enrollmentFingerprint, now, actor);
        audit.record("V4_PAPER_SHADOW_SESSION_ENROLLED", "V4_PAPER_SHADOW_SESSION",
            input.sessionId().toString(), Map.of(
                "enrollmentFingerprint", enrollmentFingerprint,
                "modelCount", registered.size(),
                "canAuthorizeCapital", false));
        return session(input.sessionId());
    }

    @Transactional(readOnly = true)
    public SessionView session(UUID id) {
        SessionView header = jdbc.sql("""
                SELECT session_id, target_spec_fingerprint, enrollment_fingerprint,
                       created_at, created_by
                  FROM v4_matched_session WHERE session_id = :id
                """)
            .param("id", id)
            .query((rs, n) -> new SessionView(
                CONTRACT, (UUID) rs.getObject("session_id"),
                rs.getString("target_spec_fingerprint"), rs.getString("enrollment_fingerprint"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getString("created_by"), List.of(), false))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown PAPER/SHADOW session"));
        List<Participant> participants = jdbc.sql("""
                SELECT model_version_id, role, model_fingerprint
                  FROM v4_matched_participant
                 WHERE session_id = :id
                 ORDER BY model_version_id
                """)
            .param("id", id)
            .query((rs, n) -> new Participant(
                (UUID) rs.getObject("model_version_id"),
                Role.valueOf(rs.getString("role")), rs.getString("model_fingerprint")))
            .list();
        return new SessionView(header.contractVersion(), header.sessionId(),
            header.targetSpecFingerprint(), header.enrollmentFingerprint(),
            header.createdAt(), header.createdBy(), participants, false);
    }

    @Transactional
    public ObservationView append(UUID sessionId, ObservationInput input) {
        requirePaper();
        // Hold a shared row lock until commit; pause/quarantine uses FOR UPDATE.
        safety.requireActiveForAppend(sessionId);
        SessionView enrollment = session(sessionId);
        if (input == null || input.caseKey() == null || !input.caseKey().matches(CASE_KEY)
                || input.symbol() == null || !input.symbol().matches("^[A-Z0-9._-]{2,30}$")) {
            throw new IllegalArgumentException("caseKey and cash-equity symbol are required");
        }
        requireHash(input.marketSourceFingerprint(), "marketSourceFingerprint");
        requireHash(input.newsSourceFingerprint(), "newsSourceFingerprint");
        requireHash(input.inputFingerprint(), "inputFingerprint");
        Instant atDecision = required(input.decisionAt(), "decisionAt");
        Instant marketAt = required(input.marketAvailableAt(), "marketAvailableAt");
        Instant newsAt = required(input.newsAvailableAt(), "newsAvailableAt");
        Instant now = clock.instant();
        if (atDecision.isBefore(enrollment.createdAt()) || atDecision.isAfter(now)
                || marketAt.isAfter(atDecision) || newsAt.isAfter(atDecision)) {
            throw new IllegalArgumentException("Decision must follow registration/source capture and predate intake");
        }
        // Once an immutable server-DB source bundle exists, old client-authored
        // fingerprints and pre-capture predictions cannot be passed off as that bundle.
        var frozen = jdbc.sql("""
                SELECT symbol, captured_at, market_available_at, news_available_at,
                       market_snapshot_fingerprint, news_snapshot_fingerprint,
                       input_fingerprint
                  FROM v4_matched_source_capture
                 WHERE session_id = :sessionId AND case_key = :caseKey
                 FOR SHARE
                """)
            .param("sessionId", sessionId).param("caseKey", input.caseKey())
            .query((rs, row) -> new FrozenSource(
                rs.getString("symbol"),
                rs.getObject("captured_at", OffsetDateTime.class).toInstant(),
                rs.getObject("market_available_at", OffsetDateTime.class).toInstant(),
                rs.getObject("news_available_at", OffsetDateTime.class).toInstant(),
                rs.getString("market_snapshot_fingerprint"),
                rs.getString("news_snapshot_fingerprint"),
                rs.getString("input_fingerprint")))
            .optional();
        if (frozen.isPresent()) {
            FrozenSource source = frozen.get();
            if (!source.symbol().equals(input.symbol())
                    || !source.marketAt().equals(marketAt)
                    || !source.newsAt().equals(newsAt)
                    || !source.marketHash().equals(input.marketSourceFingerprint())
                    || !source.newsHash().equals(input.newsSourceFingerprint())
                    || !source.inputHash().equals(input.inputFingerprint())
                    || atDecision.isBefore(source.capturedAt())) {
                throw new IllegalArgumentException("Matched decision conflicts with server-captured source evidence");
            }
        }
        if (input.decisions() == null || input.decisions().size() != enrollment.models().size()) {
            throw new IllegalArgumentException("Each matched case requires all enrolled model decisions");
        }
        Map<UUID, Candidate> candidates = new HashMap<>();
        for (Candidate candidate : input.decisions()) {
            if (candidate == null || candidate.modelVersionId() == null || candidate.action() == null
                    || candidates.putIfAbsent(candidate.modelVersionId(), candidate) != null) {
                throw new IllegalArgumentException("Duplicate or incomplete model decision");
            }
        }
        if (!candidates.keySet().equals(enrollment.models().stream()
                .map(Participant::modelVersionId).collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("Matched case roster differs from frozen session");
        }
        List<DecisionView> decisions = new ArrayList<>();
        List<String> fingerprintParts = new ArrayList<>(List.of(
            CONTRACT, enrollment.enrollmentFingerprint(), input.caseKey(),
            input.symbol(), atDecision.toString(), marketAt.toString(), newsAt.toString(),
            input.marketSourceFingerprint(), input.newsSourceFingerprint(), input.inputFingerprint()));
        for (Participant model : enrollment.models()) {
            emergency.requireModelAllowed(model.modelVersionId());
            Candidate candidate = candidates.get(model.modelVersionId());
            Instant predicted = required(candidate.predictedAt(), "predictedAt");
            requireHash(candidate.inputFingerprint(), "candidate inputFingerprint");
            if (!candidate.inputFingerprint().equals(input.inputFingerprint())) {
                throw new IllegalArgumentException("Candidates must share the exact frozen input fingerprint");
            }
            if (predicted.isBefore(marketAt) || predicted.isBefore(newsAt)
                    || (frozen.isPresent() && predicted.isBefore(frozen.get().capturedAt()))
                    || predicted.isAfter(atDecision)) {
                throw new IllegalArgumentException("Model decision is late or predates available source data");
            }
            String decisionFingerprint = ModelRegistryService.fingerprint(
                CONTRACT, enrollment.enrollmentFingerprint(), input.caseKey(),
                model.modelVersionId().toString(), model.modelFingerprint(),
                model.role().name(), candidate.action().name(), predicted.toString(),
                input.inputFingerprint());
            decisions.add(new DecisionView(
                model.modelVersionId(), model.role(), predicted,
                candidate.action(), decisionFingerprint));
            fingerprintParts.add(decisionFingerprint);
        }
        String caseFingerprint = ModelRegistryService.fingerprint(fingerprintParts.toArray(String[]::new));
        if (existsCase(sessionId, input.caseKey())) {
            ObservationView old = observation(sessionId, input.caseKey());
            if (!old.caseFingerprint().equals(caseFingerprint)) {
                throw new IllegalStateException("Immutable PAPER/SHADOW case conflicts with replay");
            }
            return old;
        }
        jdbc.sql("""
                INSERT INTO v4_matched_case (
                    session_id, case_key, symbol, decision_at, market_available_at,
                    news_available_at, market_source_fingerprint, news_source_fingerprint,
                    input_fingerprint, case_fingerprint, recorded_at)
                VALUES (
                    :id, :caseKey, :symbol, :decisionAt, :marketAt,
                    :newsAt, :marketHash, :newsHash, :inputHash, :caseHash, :recordedAt)
                """)
            .param("id", sessionId)
            .param("caseKey", input.caseKey())
            .param("symbol", input.symbol())
            .param("decisionAt", at(atDecision))
            .param("marketAt", at(marketAt))
            .param("newsAt", at(newsAt))
            .param("marketHash", input.marketSourceFingerprint())
            .param("newsHash", input.newsSourceFingerprint())
            .param("inputHash", input.inputFingerprint())
            .param("caseHash", caseFingerprint)
            .param("recordedAt", at(now))
            .update();
        for (DecisionView decision : decisions) {
            jdbc.sql("""
                    INSERT INTO v4_matched_decision (
                        session_id, case_key, model_version_id, predicted_at,
                        action, decision_fingerprint)
                    VALUES (:id, :caseKey, :versionId, :predictedAt, :action, :fingerprint)
                    """)
                .param("id", sessionId)
                .param("caseKey", input.caseKey())
                .param("versionId", decision.modelVersionId())
                .param("predictedAt", at(decision.predictedAt()))
                .param("action", decision.action().name())
                .param("fingerprint", decision.decisionFingerprint())
                .update();
        }
        audit.record("V4_PAPER_SHADOW_MATCHED_CASE_RECORDED", "V4_PAPER_SHADOW_CASE",
            sessionId + ":" + input.caseKey(), Map.of(
                "sessionId", sessionId.toString(),
                "caseFingerprint", caseFingerprint,
                "modelCount", decisions.size(),
                "canAuthorizeCapital", false));
        return observation(sessionId, input.caseKey());
    }

    @Transactional(readOnly = true)
    public ObservationView observation(UUID sessionId, String caseKey) {
        ObservationView record = jdbc.sql("""
                SELECT session_id, case_key, symbol, decision_at, market_available_at,
                       news_available_at, market_source_fingerprint, news_source_fingerprint,
                       input_fingerprint, case_fingerprint, recorded_at
                  FROM v4_matched_case
                 WHERE session_id = :id AND case_key = :caseKey
                """)
            .param("id", sessionId)
            .param("caseKey", caseKey)
            .query((rs, n) -> new ObservationView(
                CONTRACT, (UUID) rs.getObject("session_id"), rs.getString("case_key"),
                rs.getString("symbol"),
                rs.getObject("decision_at", OffsetDateTime.class).toInstant(),
                rs.getObject("market_available_at", OffsetDateTime.class).toInstant(),
                rs.getObject("news_available_at", OffsetDateTime.class).toInstant(),
                rs.getString("market_source_fingerprint"),
                rs.getString("news_source_fingerprint"), rs.getString("input_fingerprint"),
                rs.getString("case_fingerprint"),
                rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                List.of(), false, false, false))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown PAPER/SHADOW case"));
        List<DecisionView> decisions = jdbc.sql("""
                SELECT d.model_version_id, p.role, d.predicted_at,
                       d.action, d.decision_fingerprint
                  FROM v4_matched_decision d
                  JOIN v4_matched_participant p
                    ON p.session_id = d.session_id AND p.model_version_id = d.model_version_id
                 WHERE d.session_id = :id AND d.case_key = :caseKey
                 ORDER BY d.model_version_id
                """)
            .param("id", sessionId)
            .param("caseKey", caseKey)
            .query((rs, n) -> new DecisionView(
                (UUID) rs.getObject("model_version_id"),
                Role.valueOf(rs.getString("role")),
                rs.getObject("predicted_at", OffsetDateTime.class).toInstant(),
                Action.valueOf(rs.getString("action")),
                rs.getString("decision_fingerprint")))
            .list();
        return new ObservationView(
            record.contractVersion(), record.sessionId(), record.caseKey(),
            record.symbol(), record.decisionAt(), record.marketAvailableAt(), record.newsAvailableAt(),
            record.marketSourceFingerprint(), record.newsSourceFingerprint(), record.inputFingerprint(),
            record.caseFingerprint(), record.recordedAt(), decisions, false, false, false);
    }

    @Transactional(readOnly = true)
    public List<SessionView> recentSessions(int limit) {
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("session limit must be 1-50");
        }
        return jdbc.sql("""
                SELECT session_id
                  FROM v4_matched_session
                 ORDER BY created_at DESC, session_id DESC
                 LIMIT :limit
                """)
            .param("limit", limit)
            .query(UUID.class)
            .list()
            .stream()
            .map(this::session)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<ObservationView> recent(UUID sessionId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1-100");
        session(sessionId);
        return jdbc.sql("""
                SELECT case_key FROM v4_matched_case
                 WHERE session_id = :id
                 ORDER BY decision_at DESC, case_key DESC
                 LIMIT :limit
                """)
            .param("id", sessionId)
            .param("limit", limit)
            .query(String.class)
            .list()
            .stream()
            .map(key -> observation(sessionId, key))
            .toList();
    }

    private boolean existsSession(UUID id) {
        return jdbc.sql("SELECT count(*) FROM v4_matched_session WHERE session_id = :id")
            .param("id", id).query(Integer.class).single() > 0;
    }

    private boolean existsCase(UUID id, String caseKey) {
        return jdbc.sql("""
                SELECT count(*) FROM v4_matched_case WHERE session_id = :id AND case_key = :caseKey
                """)
            .param("id", id).param("caseKey", caseKey).query(Integer.class).single() > 0;
    }

    private void requirePaper() {
        if (!TradingExecutionAuthority.PAPER.equals(authority.current().tradingMode())) {
            throw new IllegalStateException("Matched research intake is PAPER-only");
        }
    }

    private static Instant required(Instant value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches(HASH)) {
            throw new IllegalArgumentException(field + " requires a lowercase SHA-256");
        }
    }

    private static OffsetDateTime at(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static String actor() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return "SYSTEM";
        }
        String name = auth.getName();
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalStateException("Invalid authenticated actor");
        }
        return name;
    }
}
