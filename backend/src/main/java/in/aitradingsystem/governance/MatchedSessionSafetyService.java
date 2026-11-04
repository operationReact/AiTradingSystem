package in.aitradingsystem.governance;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.execution.TradingExecutionAuthority;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL-row-locked, Java-authoritative session safety state.
 *
 * ACTIVE -> PAUSED -> ACTIVE is reversible only after governance revalidation.
 * QUARANTINED is terminal: start a new independently enrolled session to recover.
 * Nothing in this service grants LIVE, broker, model promotion or capital authority.
 */
@Service
public class MatchedSessionSafetyService {
    public static final String CONTRACT = "v4-matched-session-safety.v1";

    public enum State { ACTIVE, PAUSED, QUARANTINED }
    public enum Action { PAUSE, RESUME, QUARANTINE }

    public record StateView(
        String contractVersion, UUID sessionId, State state, long revision,
        String stateFingerprint, String reason, Instant updatedAt,
        String updatedBy, boolean canAuthorizeCapital) {}

    public record TransitionView(
        String contractVersion, UUID sessionId, long revision,
        State previousState, State newState, String reason,
        String stateFingerprint, String previousFingerprint, Instant changedAt,
        String changedBy, boolean canAuthorizeCapital) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;
    private final ModelEmergencyControlService emergency;
    private final TradingExecutionAuthority tradingAuthority;

    public MatchedSessionSafetyService(
            JdbcClient jdbc, AuditService audit, Clock clock,
            ModelEmergencyControlService emergency, TradingExecutionAuthority tradingAuthority) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.emergency = emergency;
        this.tradingAuthority = tradingAuthority;
    }

    /**
     * Must participate in the enclosing enrollment transaction, after session insert.
     * Existing sessions were backfilled in Flyway V58.
     */
    public void registerEnrollment(UUID sessionId, String enrollmentFingerprint,
            Instant enrolledAt, String enrolledBy) {
        String reason = "Initial enrollment";
        jdbc.sql("""
                INSERT INTO v4_matched_session_state (
                    session_id, state, revision, state_fingerprint,
                    reason, updated_at, updated_by)
                VALUES (:id, 'ACTIVE', 0, :fingerprint, :reason, :at, :actor)
                """)
            .param("id", sessionId)
            .param("fingerprint", enrollmentFingerprint)
            .param("reason", reason)
            .param("at", utc(enrolledAt))
            .param("actor", enrolledBy)
            .update();
        jdbc.sql("""
                INSERT INTO v4_matched_session_transition (
                    session_id, revision, previous_state, new_state,
                    reason, state_fingerprint, previous_fingerprint,
                    changed_at, changed_by)
                VALUES (:id, 0, NULL, 'ACTIVE', :reason, :fingerprint, NULL, :at, :actor)
                """)
            .param("id", sessionId)
            .param("reason", reason)
            .param("fingerprint", enrollmentFingerprint)
            .param("at", utc(enrolledAt))
            .param("actor", enrolledBy)
            .update();
    }

    @Transactional(readOnly = true)
    public StateView state(UUID sessionId) {
        return jdbc.sql("""
                SELECT session_id, state, revision, state_fingerprint,
                       reason, updated_at, updated_by
                  FROM v4_matched_session_state
                 WHERE session_id = :id
                """)
            .param("id", sessionId)
            .query((rs, rowNum) -> new StateView(
                CONTRACT, (UUID) rs.getObject("session_id"),
                State.valueOf(rs.getString("state")), rs.getLong("revision"),
                rs.getString("state_fingerprint"), rs.getString("reason"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getString("updated_by"), false))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown matched session safety state"));
    }

    /**
     * FOR SHARE pins the state through the enclosing append transaction. PAUSE or
     * QUARANTINE obtains an exclusive row lock; therefore an append cannot cross
     * a committed state transition without observing it, even under READ COMMITTED.
     */
    public void requireActiveForAppend(UUID sessionId) {
        State state = jdbc.sql("""
                SELECT state FROM v4_matched_session_state
                 WHERE session_id = :id
                 FOR SHARE
                """)
            .param("id", sessionId)
            .query(String.class)
            .optional()
            .map(State::valueOf)
            .orElseThrow(() -> new IllegalStateException(
                "Missing matched session safety gate: fail closed"));
        if (state != State.ACTIVE) {
            throw new IllegalStateException("Matched session does not accept new evidence: " + state);
        }
    }

    @Transactional
    public StateView transition(UUID sessionId, Action action, String rawReason) {
        if (sessionId == null || action == null) {
            throw new IllegalArgumentException("Session ID and action are required");
        }
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.length() < 10 || reason.length() > 512) {
            throw new IllegalArgumentException("Audit reason must contain 10-512 characters");
        }

        // Exclusive lock serializes operator actions and evidence append's shared lock.
        StateView prior = jdbc.sql("""
                SELECT session_id, state, revision, state_fingerprint,
                       reason, updated_at, updated_by
                  FROM v4_matched_session_state
                 WHERE session_id = :id FOR UPDATE
                """)
            .param("id", sessionId)
            .query((rs, rowNum) -> new StateView(
                CONTRACT, (UUID) rs.getObject("session_id"),
                State.valueOf(rs.getString("state")), rs.getLong("revision"),
                rs.getString("state_fingerprint"), rs.getString("reason"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getString("updated_by"), false))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown matched session safety state"));

        State next = switch (action) {
            case PAUSE -> State.PAUSED;
            case RESUME -> State.ACTIVE;
            case QUARANTINE -> State.QUARANTINED;
        };
        boolean permitted = switch (action) {
            case PAUSE -> prior.state() == State.ACTIVE;
            case RESUME -> prior.state() == State.PAUSED;
            case QUARANTINE -> prior.state() == State.ACTIVE || prior.state() == State.PAUSED;
        };
        if (!permitted) {
            throw new IllegalStateException(
                "Invalid matched session transition: " + prior.state() + " via " + action);
        }
        if (action == Action.RESUME) {
            if (!TradingExecutionAuthority.PAPER.equals(tradingAuthority.current().tradingMode())) {
                throw new IllegalStateException("Only PAPER authority can resume matched evidence");
            }
            List<UUID> models = jdbc.sql("""
                    SELECT model_version_id FROM v4_matched_participant WHERE session_id = :id
                    """)
                .param("id", sessionId)
                .query(UUID.class)
                .list();
            if (models.size() < 2) {
                throw new IllegalStateException("Enrolled model roster is incomplete");
            }
            for (UUID modelVersionId : models) emergency.requireModelAllowed(modelVersionId);
        }

        Instant now = clock.instant();
        String actor = currentActor();
        long revision = prior.revision() + 1;
        String fingerprint = ModelRegistryService.fingerprint(
            CONTRACT, sessionId.toString(), prior.stateFingerprint(),
            Long.toString(revision), next.name(), reason, actor, now.toString());
        jdbc.sql("""
                UPDATE v4_matched_session_state
                   SET state = :next, revision = :revision,
                       state_fingerprint = :fingerprint,
                       reason = :reason, updated_at = :at, updated_by = :actor
                 WHERE session_id = :id AND revision = :priorRevision
                """)
            .param("next", next.name())
            .param("revision", revision)
            .param("fingerprint", fingerprint)
            .param("reason", reason)
            .param("at", utc(now))
            .param("actor", actor)
            .param("id", sessionId)
            .param("priorRevision", prior.revision())
            .update();
        jdbc.sql("""
                INSERT INTO v4_matched_session_transition (
                    session_id, revision, previous_state, new_state, reason,
                    state_fingerprint, previous_fingerprint, changed_at, changed_by)
                VALUES (:id, :revision, :previousState, :next, :reason,
                    :fingerprint, :previousFingerprint, :at, :actor)
                """)
            .param("id", sessionId)
            .param("revision", revision)
            .param("previousState", prior.state().name())
            .param("next", next.name())
            .param("reason", reason)
            .param("fingerprint", fingerprint)
            .param("previousFingerprint", prior.stateFingerprint())
            .param("at", utc(now))
            .param("actor", actor)
            .update();
        audit.record("V4_MATCHED_SESSION_SAFETY_CHANGED", "V4_PAPER_SHADOW_SESSION",
            sessionId.toString(), Map.of(
                "previousState", prior.state().name(), "newState", next.name(),
                "previousFingerprint", prior.stateFingerprint(),
                "newFingerprint", fingerprint, "revision", revision,
                "reason", reason, "canAuthorizeCapital", false));
        return state(sessionId);
    }

    @Transactional(readOnly = true)
    public List<TransitionView> history(UUID sessionId, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Transition history limit must be 1-100");
        }
        state(sessionId);
        return jdbc.sql("""
                SELECT session_id, revision, previous_state, new_state, reason,
                       state_fingerprint, previous_fingerprint, changed_at, changed_by
                  FROM v4_matched_session_transition
                 WHERE session_id = :id
                 ORDER BY revision DESC LIMIT :limit
                """)
            .param("id", sessionId)
            .param("limit", limit)
            .query((rs, rowNum) -> new TransitionView(
                CONTRACT, (UUID) rs.getObject("session_id"),
                rs.getLong("revision"),
                rs.getString("previous_state") == null ? null
                    : State.valueOf(rs.getString("previous_state")),
                State.valueOf(rs.getString("new_state")),
                rs.getString("reason"), rs.getString("state_fingerprint"),
                rs.getString("previous_fingerprint"),
                rs.getObject("changed_at", OffsetDateTime.class).toInstant(),
                rs.getString("changed_by"), false))
            .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) return "SYSTEM";
        String actor = authentication.getName();
        if (actor == null || actor.isBlank() || actor.length() > 120) {
            throw new IllegalStateException("Invalid audit actor");
        }
        return actor;
    }
}
