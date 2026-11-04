package in.aitradingsystem.governance;

import in.aitradingsystem.audit.AuditService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable point-in-time journal for a frozen PAPER champion and SHADOW challengers.
 * The journal never invokes model inference, modifies an order or grants LIVE authority.
 * Runtime provenance is operator-declared; independent source/soak acceptance is separate.
 */
@Service
public class PaperShadowDecisionJournalService {
    public static final String VERSION = "v4-paper-shadow-journal.v1";
    private static final String HASH = "^[a-f0-9]{64}$";
    private static final String CASE_KEY = "^[A-Za-z0-9][A-Za-z0-9._:-]{2,95}$";
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final ModelEmergencyControlService controls;
    private final Clock clock;

    public PaperShadowDecisionJournalService(
            JdbcClient jdbc, AuditService audit, ModelEmergencyControlService controls, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.controls = controls;
        this.clock = clock;
    }

    public record Enrollment(UUID modelVersionId, String role) {}
    public record CreateSession(String sourceKind, String cohortFingerprint,
                                String targetFingerprint, List<Enrollment> models) {}
    public record ModelSignal(UUID modelVersionId, Instant predictedAt, String action,
                              String modelEvidenceFingerprint) {}
    public record CaptureCase(String caseKey, String symbol, Instant decisionAt,
                              Instant marketAvailableAt, Instant newsAvailableAt,
                              String marketFingerprint, String newsFingerprint,
                              String inputFingerprint, List<ModelSignal> decisions) {}
    public record ModelView(UUID modelVersionId, String role, String modelFingerprint) {}
    public record SessionView(String contractVersion, UUID sessionId, String sourceKind,
                              String cohortFingerprint, String targetFingerprint,
                              String sessionFingerprint, Instant createdAt,
                              List<ModelView> models, boolean provenanceVerified,
                              boolean canAuthorizeCapital) {}
    public record DecisionView(UUID modelVersionId, String role, String action,
                               Instant predictedAt, String modelEvidenceFingerprint,
                               String decisionFingerprint) {}
    public record CaseView(String contractVersion, UUID sessionId, String caseKey, String symbol,
                           Instant decisionAt, Instant marketAvailableAt, Instant newsAvailableAt,
                           String inputFingerprint, String marketFingerprint, String newsFingerprint,
                           String caseFingerprint, List<DecisionView> decisions,
                           UUID paperOrderId, String paperOrderStatus, int paperFillCount,
                           BigDecimal paperFeeAmount, BigDecimal paperSlippageAmount,
                           boolean sourceCustodyVerified, boolean liveOrdersAllowed,
                           boolean canAuthorizeCapital) {}

    @Transactional
    public SessionView createSession(CreateSession input) {
        if (input == null || input.models() == null || input.models().size() < 2
                || input.models().size() > 10) {
            throw new IllegalArgumentException("Between 2 and 10 registered models are required");
        }
        String source = exact(input.sourceKind(), Set.of("CI_SYNTHETIC", "RUNTIME_EXPORT"), "source kind");
        String cohort = hash(input.cohortFingerprint());
        String target = hash(input.targetFingerprint());
        Set<UUID> ids = new HashSet<>();
        List<ModelView> roster = new ArrayList<>();
        int champions = 0;
        for (Enrollment member : input.models()) {
            if (member == null || member.modelVersionId() == null || !ids.add(member.modelVersionId())) {
                throw new IllegalArgumentException("Every model version must be unique");
            }
            String role = exact(member.role(), Set.of("PAPER_CHAMPION", "SHADOW_CHALLENGER"), "model role");
            if ("PAPER_CHAMPION".equals(role)) champions++;
            controls.requireModelAllowed(member.modelVersionId());
            String fingerprint = jdbc.sql("SELECT version_fingerprint FROM model_version WHERE version_id = :id")
                .param("id", member.modelVersionId())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new NoSuchElementException("Unknown registered model version"));
            roster.add(new ModelView(member.modelVersionId(), role, fingerprint));
        }
        if (champions != 1) throw new IllegalArgumentException("Exactly one PAPER champion is required");
        roster.sort(Comparator.comparing(item -> item.modelVersionId().toString()));
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String fingerprint = ModelRegistryService.fingerprint(
            VERSION, id.toString(), source, cohort, target, now.toString(),
            roster.stream().map(item -> item.modelVersionId() + ":" + item.role()
                + ":" + item.modelFingerprint()).reduce("", (a, b) -> a + "|" + b));
        jdbc.sql("""
                INSERT INTO v4_paper_shadow_session
                    (session_id, source_kind, cohort_fingerprint, target_fingerprint,
                     session_fingerprint, created_at, created_by)
                VALUES (:id, :source, :cohort, :target, :fingerprint, :createdAt, :actor)
                """)
            .param("id", id).param("source", source).param("cohort", cohort)
            .param("target", target).param("fingerprint", fingerprint)
            .param("createdAt", ts(now)).param("actor", actor()).update();
        for (ModelView model : roster) {
            jdbc.sql("""
                    INSERT INTO v4_paper_shadow_model
                        (session_id, model_version_id, model_fingerprint, role)
                    VALUES (:id, :versionId, :fingerprint, :role)
                    """)
                .param("id", id).param("versionId", model.modelVersionId())
                .param("fingerprint", model.modelFingerprint()).param("role", model.role())
                .update();
        }
        audit.record("V4_PAPER_SHADOW_SESSION_CREATED", "PAPER_SHADOW_SESSION", id.toString(),
            Map.of("sessionFingerprint", fingerprint, "sourceKind", source,
                "models", roster.size(), "canAuthorizeCapital", false));
        return session(id);
    }

    @Transactional(readOnly = true)
    public SessionView session(UUID id) {
        SessionHeader header = jdbc.sql("""
                SELECT source_kind, cohort_fingerprint, target_fingerprint,
                       session_fingerprint, created_at
                  FROM v4_paper_shadow_session WHERE session_id = :id
                """)
            .param("id", id)
            .query((rs, row) -> new SessionHeader(
                rs.getString("source_kind"), rs.getString("cohort_fingerprint"),
                rs.getString("target_fingerprint"), rs.getString("session_fingerprint"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown PAPER/SHADOW session"));
        List<ModelView> models = jdbc.sql("""
                SELECT model_version_id, role, model_fingerprint
                  FROM v4_paper_shadow_model WHERE session_id = :id
                 ORDER BY model_version_id
                """)
            .param("id", id)
            .query((rs, row) -> new ModelView(
                rs.getObject("model_version_id", UUID.class),
                rs.getString("role"), rs.getString("model_fingerprint")))
            .list();
        return new SessionView(VERSION, id, header.source(), header.cohort(),
            header.target(), header.fingerprint(), header.createdAt(),
            models, false, false);
    }

    @Transactional
    public CaseView capture(UUID sessionId, CaptureCase input) {
        SessionView session = session(sessionId);
        if (input == null || input.decisions() == null || input.decisions().size() != session.models().size()) {
            throw new IllegalArgumentException("Every enrolled model must submit exactly one decision");
        }
        if (input.caseKey() == null || !input.caseKey().matches(CASE_KEY)) {
            throw new IllegalArgumentException("Invalid case key");
        }
        String symbol = input.symbol() == null ? "" : input.symbol().trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("^[A-Z0-9][A-Z0-9._-]{1,39}$")) {
            throw new IllegalArgumentException("Invalid market symbol");
        }
        Instant now = clock.instant();
        if (input.decisionAt() == null || input.marketAvailableAt() == null || input.newsAvailableAt() == null
            || input.decisionAt().isBefore(session.createdAt()) || input.decisionAt().isAfter(now)
            || input.marketAvailableAt().isAfter(input.decisionAt())
            || input.newsAvailableAt().isAfter(input.decisionAt())) {
            throw new IllegalArgumentException("Model decisions must follow available source data and session registration");
        }
        String market = hash(input.marketFingerprint());
        String news = hash(input.newsFingerprint());
        String sourceInput = hash(input.inputFingerprint());
        Map<UUID, ModelView> enrolled = session.models().stream()
            .collect(java.util.stream.Collectors.toMap(ModelView::modelVersionId, member -> member));
        Set<UUID> seen = new HashSet<>();
        List<SignalRecord> signals = new ArrayList<>();
        for (ModelSignal signal : input.decisions()) {
            if (signal == null || signal.modelVersionId() == null
                || !enrolled.containsKey(signal.modelVersionId()) || !seen.add(signal.modelVersionId())) {
                throw new IllegalArgumentException("Duplicate or unregistered model in decision cohort");
            }
            if (signal.predictedAt() == null || signal.predictedAt().isBefore(session.createdAt())
                || signal.predictedAt().isBefore(input.marketAvailableAt())
                || signal.predictedAt().isBefore(input.newsAvailableAt())
                || signal.predictedAt().isAfter(input.decisionAt())) {
                throw new IllegalArgumentException("Candidate prediction is late or predates available market/news evidence");
            }
            String action = exact(signal.action(), Set.of("ENTER", "WAIT", "ABSTAIN"), "model action");
            String evidence = hash(signal.modelEvidenceFingerprint());
            ModelView enrolledModel = enrolled.get(signal.modelVersionId());
            String fingerprint = ModelRegistryService.fingerprint(
                VERSION, input.caseKey(), signal.modelVersionId().toString(),
                enrolledModel.modelFingerprint(), enrolledModel.role(),
                action, signal.predictedAt().toString(), sourceInput, evidence);
            signals.add(new SignalRecord(signal.modelVersionId(), enrolledModel.role(),
                action, signal.predictedAt(), evidence, fingerprint));
        }
        signals.sort(Comparator.comparing(item -> item.modelVersionId().toString()));
        String combined = signals.stream().map(SignalRecord::fingerprint).reduce("", (a, b) -> a + "|" + b);
        String fingerprint = ModelRegistryService.fingerprint(
            VERSION, session.sessionFingerprint(), input.caseKey(), symbol,
            input.decisionAt().toString(), input.marketAvailableAt().toString(),
            input.newsAvailableAt().toString(), market, news, sourceInput, combined);
        String old = jdbc.sql("""
                SELECT case_fingerprint FROM v4_paper_shadow_case
                 WHERE session_id = :sessionId AND case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", input.caseKey())
            .query(String.class).optional().orElse(null);
        if (old != null) {
            if (!old.equals(fingerprint)) {
                throw new IllegalStateException("An immutable decision case cannot be replaced");
            }
            return evidence(sessionId, input.caseKey());
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO v4_paper_shadow_case
                    (case_id, session_id, case_key, symbol, decision_at, market_available_at,
                     news_available_at, market_fingerprint, news_fingerprint,
                     input_fingerprint, case_fingerprint, recorded_at)
                VALUES (:id, :sessionId, :caseKey, :symbol, :decisionAt, :marketAt,
                        :newsAt, :market, :news, :input, :fingerprint, :recordedAt)
                """)
            .param("id", id).param("sessionId", sessionId).param("caseKey", input.caseKey())
            .param("symbol", symbol).param("decisionAt", ts(input.decisionAt()))
            .param("marketAt", ts(input.marketAvailableAt())).param("newsAt", ts(input.newsAvailableAt()))
            .param("market", market).param("news", news).param("input", sourceInput)
            .param("fingerprint", fingerprint).param("recordedAt", ts(now)).update();
        for (SignalRecord signal : signals) {
            jdbc.sql("""
                    INSERT INTO v4_paper_shadow_decision
                        (decision_id, session_id, case_id, model_version_id, role,
                         predicted_at, action, model_evidence_fingerprint, decision_fingerprint)
                    VALUES (:id, :sessionId, :caseId, :versionId, :role,
                            :predictedAt, :action, :evidence, :fingerprint)
                    """)
                .param("id", UUID.randomUUID()).param("sessionId", sessionId).param("caseId", id)
                .param("versionId", signal.modelVersionId()).param("role", signal.role())
                .param("predictedAt", ts(signal.predictedAt())).param("action", signal.action())
                .param("evidence", signal.evidence()).param("fingerprint", signal.fingerprint())
                .update();
        }
        audit.record("V4_PAPER_SHADOW_CASE_RECORDED", "PAPER_SHADOW_CASE", id.toString(),
            Map.of("sessionId", sessionId.toString(), "caseFingerprint", fingerprint,
                "candidateCount", signals.size(), "canAuthorizeCapital", false));
        return evidence(sessionId, input.caseKey());
    }

    /**
     * Attach an ALREADY-EXISTING automated PAPER order for the ENTER champion.
     * This is an audit link only; it does not submit, alter, approve, or settle orders.
     */
    @Transactional
    public CaseView linkPaperOrder(UUID sessionId, String caseKey, UUID orderId) {
        CaseView caseView = evidence(sessionId, caseKey);
        if (caseView.decisions().stream().noneMatch(
                decision -> "PAPER_CHAMPION".equals(decision.role()) && "ENTER".equals(decision.action()))) {
            throw new IllegalStateException("Only an ENTER PAPER champion can link a PAPER order");
        }
        OrderIdentity order = jdbc.sql("""
                SELECT symbol, side, execution_origin, created_at
                  FROM paper_order WHERE id = :orderId
                """)
            .param("orderId", orderId)
            .query((rs, row) -> new OrderIdentity(
                rs.getString("symbol"), rs.getString("side"), rs.getString("execution_origin"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()))
            .optional().orElseThrow(() -> new NoSuchElementException("Unknown PAPER order"));
        if (!caseView.symbol().equalsIgnoreCase(order.symbol()) || !"BUY".equals(order.side())
                || !"AUTOMATED".equals(order.origin())
                || order.createdAt().isBefore(caseView.decisionAt())) {
            throw new IllegalArgumentException("PAPER order must match an automated BUY after the recorded decision");
        }
        if (caseView.paperOrderId() != null) {
            if (caseView.paperOrderId().equals(orderId)) return caseView;
            throw new IllegalStateException("PAPER order link is immutable");
        }
        UUID caseId = jdbc.sql("""
                SELECT case_id FROM v4_paper_shadow_case
                 WHERE session_id = :sessionId AND case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO v4_paper_shadow_order_link (case_id, order_id, linked_at, linked_by)
                VALUES (:caseId, :orderId, :linkedAt, :actor)
                """)
            .param("caseId", caseId).param("orderId", orderId)
            .param("linkedAt", ts(clock.instant())).param("actor", actor()).update();
        audit.record("V4_PAPER_SHADOW_PAPER_ORDER_LINKED", "PAPER_SHADOW_CASE", caseId.toString(),
            Map.of("orderId", orderId.toString(), "sessionId", sessionId.toString(),
                "canAuthorizeCapital", false));
        return evidence(sessionId, caseKey);
    }

    @Transactional(readOnly = true)
    public CaseView evidence(UUID sessionId, String caseKey) {
        CaseHeader row = jdbc.sql("""
                SELECT c.case_id, c.symbol, c.decision_at, c.market_available_at, c.news_available_at,
                       c.input_fingerprint, c.market_fingerprint, c.news_fingerprint, c.case_fingerprint,
                       l.order_id, o.status,
                       COUNT(f.id) AS fill_count,
                       COALESCE(SUM(f.fee_amount), 0) AS total_fees,
                       COALESCE(SUM(f.slippage_amount), 0) AS total_slippage
                  FROM v4_paper_shadow_case c
                  LEFT JOIN v4_paper_shadow_order_link l ON l.case_id = c.case_id
                  LEFT JOIN paper_order o ON o.id = l.order_id
                  LEFT JOIN paper_fill f ON f.order_id = l.order_id
                 WHERE c.session_id = :sessionId AND c.case_key = :caseKey
                 GROUP BY c.case_id, l.order_id, o.status
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query((rs, rowIndex) -> mapCase(rs))
            .optional().orElseThrow(() -> new NoSuchElementException("Unknown PAPER/SHADOW case"));
        List<DecisionView> decisions = jdbc.sql("""
                SELECT model_version_id, role, action, predicted_at,
                       model_evidence_fingerprint, decision_fingerprint
                  FROM v4_paper_shadow_decision
                 WHERE case_id = :caseId ORDER BY model_version_id
                """)
            .param("caseId", row.id())
            .query((rs, index) -> new DecisionView(
                rs.getObject("model_version_id", UUID.class), rs.getString("role"),
                rs.getString("action"), rs.getObject("predicted_at", OffsetDateTime.class).toInstant(),
                rs.getString("model_evidence_fingerprint"), rs.getString("decision_fingerprint")))
            .list();
        return new CaseView(VERSION, sessionId, caseKey, row.symbol(), row.decisionAt(),
            row.marketAt(), row.newsAt(), row.input(), row.market(), row.news(),
            row.fingerprint(), List.copyOf(decisions), row.orderId(), row.status(),
            row.fillCount(), row.fees(), row.slippage(), false, false, false);
    }

    @Transactional(readOnly = true)
    public List<CaseView> recent(UUID sessionId, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Limit must be 1..500");
        session(sessionId);
        List<String> keys = jdbc.sql("""
                SELECT case_key FROM v4_paper_shadow_case WHERE session_id = :sessionId
                 ORDER BY decision_at DESC, case_key DESC LIMIT :limit
                """)
            .param("sessionId", sessionId).param("limit", limit)
            .query(String.class).list();
        return keys.stream().map(key -> evidence(sessionId, key)).toList();
    }

    private static CaseHeader mapCase(ResultSet rs) throws SQLException {
        return new CaseHeader(
            rs.getObject("case_id", UUID.class), rs.getString("symbol"),
            rs.getObject("decision_at", OffsetDateTime.class).toInstant(),
            rs.getObject("market_available_at", OffsetDateTime.class).toInstant(),
            rs.getObject("news_available_at", OffsetDateTime.class).toInstant(),
            rs.getString("input_fingerprint"), rs.getString("market_fingerprint"),
            rs.getString("news_fingerprint"), rs.getString("case_fingerprint"),
            rs.getObject("order_id", UUID.class), rs.getString("status"), rs.getInt("fill_count"),
            rs.getBigDecimal("total_fees"), rs.getBigDecimal("total_slippage"));
    }

    private static String hash(String value) {
        if (value == null || !value.matches(HASH))
            throw new IllegalArgumentException("Exact lowercase SHA-256 fingerprint required");
        return value;
    }

    private static String exact(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value;
    }

    private static String actor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken
                || auth.getName() == null || auth.getName().isBlank()) {
            throw new IllegalStateException("An authenticated administrator is required");
        }
        return auth.getName();
    }

    private static OffsetDateTime ts(Instant value) { return value.atOffset(ZoneOffset.UTC); }

    private record SessionHeader(String source, String cohort, String target, String fingerprint, Instant createdAt) {}
    private record OrderIdentity(String symbol, String side, String origin, Instant createdAt) {}
    private record SignalRecord(UUID modelVersionId, String role, String action, Instant predictedAt,
                                String evidence, String fingerprint) {}
    private record CaseHeader(UUID id, String symbol, Instant decisionAt, Instant marketAt, Instant newsAt,
                              String input, String market, String news, String fingerprint,
                              UUID orderId, String status, int fillCount, BigDecimal fees, BigDecimal slippage) {}
}
