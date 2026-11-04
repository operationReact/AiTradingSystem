package in.aitradingsystem.paper;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.governance.ModelRegistryService;
import java.math.BigDecimal;
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
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Immutable ADMIN-only intake of matched model decisions and links to existing PAPER
 * orders. Never calls PaperOrderService or a broker. An API-supplied timestamp or hash
 * is not independently authenticated data custody or proof of continuous runtime.
 */
@Service
public class PaperShadowDecisionEvidenceService {
    public static final String CONTRACT = "paper-shadow-decision-evidence.v1";
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{2,95}$");
    private static final Pattern SYMBOL = Pattern.compile("^[A-Z0-9][A-Z0-9.-]{1,19}$");
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;

    public PaperShadowDecisionEvidenceService(JdbcClient jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    public enum CandidateRole { PAPER_CHAMPION, SHADOW_CHALLENGER }
    public enum CandidateAction { ENTER, WAIT, ABSTAIN }

    public record CandidateCapture(UUID modelVersionId, CandidateRole role, CandidateAction action,
                                   Instant predictedAt, UUID paperOrderId) {}

    public record CaseCapture(String caseKey, String symbol, Instant marketAvailableAt,
                              Instant newsAvailableAt, Instant decisionAt, String marketSourceFingerprint,
                              String newsSourceFingerprint, String inputFingerprint,
                              List<CandidateCapture> candidates) {}

    public record CandidateEvidence(UUID modelVersionId, String modelVersionFingerprint,
                                    CandidateRole role, CandidateAction action, Instant predictedAt,
                                    UUID paperOrderId, String paperOrderStatus, Integer filledQuantity,
                                    BigDecimal paperFeeAmount, BigDecimal paperSlippageAmount,
                                    String decisionFingerprint) {}

    public record CaseEvidence(UUID caseId, String contractVersion, String caseKey, String symbol,
                               Instant marketAvailableAt, Instant newsAvailableAt, Instant decisionAt,
                               String marketSourceFingerprint, String newsSourceFingerprint,
                               String inputFingerprint, String caseFingerprint, Instant recordedAt,
                               String recordedBy, List<CandidateEvidence> candidates,
                               boolean sourceCustodyIndependentlyVerified, boolean realRuntimeSoakVerified,
                               boolean liveOrdersAllowed, boolean canAuthorizeCapital) {}

    private record EligibleCandidate(UUID versionId, String versionFingerprint,
                                     CandidateRole role, CandidateAction action,
                                     Instant predictedAt, UUID paperOrderId, String fingerprint) {}

    /** Duplicate case-key retry is read-only when all frozen evidence is identical. */
    @Transactional
    public CaseEvidence capture(CaseCapture request) {
        Objects.requireNonNull(request, "case evidence is required");
        if (request.caseKey() == null || !KEY.matcher(request.caseKey()).matches()) {
            throw new IllegalArgumentException("Invalid bounded PAPER/SHADOW case key");
        }
        if (request.symbol() == null) throw new IllegalArgumentException("Symbol is required");
        String symbol = request.symbol().trim().toUpperCase(Locale.ROOT);
        if (!SYMBOL.matcher(symbol).matches()) throw new IllegalArgumentException("Invalid cash-equity symbol");
        requireHash(request.marketSourceFingerprint(), "market source");
        requireHash(request.newsSourceFingerprint(), "news source");
        requireHash(request.inputFingerprint(), "matched input");
        Instant marketAvailable = Objects.requireNonNull(request.marketAvailableAt(), "market time required");
        Instant newsAvailable = Objects.requireNonNull(request.newsAvailableAt(), "news time required");
        Instant decisionAt = Objects.requireNonNull(request.decisionAt(), "decision time required");
        Instant now = clock.instant();
        if (marketAvailable.isAfter(decisionAt) || newsAvailable.isAfter(decisionAt)
                || decisionAt.isAfter(now)) {
            throw new IllegalArgumentException("Sources and model decisions must be available by decision time");
        }
        if (request.candidates() == null || request.candidates().size() < 2
                || request.candidates().size() > 10) {
            throw new IllegalArgumentException("One PAPER champion and 1-9 SHADOW challengers are required");
        }
        List<CandidateCapture> inputs = new ArrayList<>(request.candidates());
        if (inputs.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Null candidate is not allowed");
        }
        inputs.sort(Comparator.comparing(input -> Objects.requireNonNull(
            input.modelVersionId(), "candidate model version required")));
        List<EligibleCandidate> candidates = new ArrayList<>();
        HashSet<UUID> versionIds = new HashSet<>();
        HashSet<UUID> linkedOrders = new HashSet<>();
        int champions = 0;
        for (CandidateCapture input : inputs) {
            if (!versionIds.add(input.modelVersionId())) {
                throw new IllegalArgumentException("Duplicate model version in matched cohort");
            }
            CandidateRole role = Objects.requireNonNull(input.role(), "candidate role required");
            CandidateAction action = Objects.requireNonNull(input.action(), "candidate action required");
            Instant predictedAt = Objects.requireNonNull(input.predictedAt(), "prediction timestamp required");
            if (predictedAt.isBefore(marketAvailable) || predictedAt.isBefore(newsAvailable)
                    || predictedAt.isAfter(decisionAt)) {
                throw new IllegalArgumentException("Model prediction is outside the common causal decision window");
            }
            if (role == CandidateRole.PAPER_CHAMPION) champions++;
            UUID linkedOrder = input.paperOrderId();
            if (linkedOrder != null) {
                if (role != CandidateRole.PAPER_CHAMPION || action != CandidateAction.ENTER) {
                    throw new IllegalArgumentException("Only an ENTER PAPER champion can link an order");
                }
                if (!linkedOrders.add(linkedOrder)) {
                    throw new IllegalArgumentException("Duplicate linked PAPER order");
                }
                requireExistingPaperOrder(linkedOrder, symbol, decisionAt);
            }
            String versionFingerprint = jdbc.sql(
                    "SELECT version_fingerprint FROM model_version WHERE version_id = :id")
                .param("id", input.modelVersionId())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new NoSuchElementException(
                    "Unknown registered model version: " + input.modelVersionId()));
            String candidateFingerprint = ModelRegistryService.fingerprint(CONTRACT,
                request.caseKey(), input.modelVersionId().toString(),
                versionFingerprint, role.name(), action.name(), predictedAt.toString(),
                request.inputFingerprint(), linkedOrder == null ? "" : linkedOrder.toString());
            candidates.add(new EligibleCandidate(input.modelVersionId(), versionFingerprint,
                role, action, predictedAt, linkedOrder, candidateFingerprint));
        }
        if (champions != 1) {
            throw new IllegalArgumentException("Exactly one PAPER champion is required");
        }
        String caseFingerprint = ModelRegistryService.fingerprint(
            CONTRACT, request.caseKey(), symbol, marketAvailable.toString(), newsAvailable.toString(),
            decisionAt.toString(), request.marketSourceFingerprint(),
            request.newsSourceFingerprint(), request.inputFingerprint(),
            String.join("|", candidates.stream().map(EligibleCandidate::fingerprint).toList()));

        var found = jdbc.sql("""
                SELECT case_id, case_fingerprint FROM paper_shadow_decision_case
                 WHERE case_key = :key
                """)
            .param("key", request.caseKey())
            .query((rs, rowNum) -> new ExistingCase(rs.getObject("case_id", UUID.class),
                rs.getString("case_fingerprint")))
            .optional();
        if (found.isPresent()) {
            if (!found.get().fingerprint().equals(caseFingerprint)) {
                throw new IllegalArgumentException("Conflicting replay of immutable PAPER/SHADOW case key");
            }
            return byId(found.get().id());
        }

        UUID caseId = UUID.randomUUID();
        String actor = currentActor();
        jdbc.sql("""
                INSERT INTO paper_shadow_decision_case (
                    case_id, case_key, symbol, market_available_at, news_available_at, decision_at,
                    market_source_fingerprint, news_source_fingerprint, input_fingerprint,
                    case_fingerprint, recorded_at, recorded_by)
                VALUES (:id, :key, :symbol, :marketAt, :newsAt, :decisionAt, :marketHash,
                    :newsHash, :inputHash, :fingerprint, :recordedAt, :actor)
                """)
            .param("id", caseId).param("key", request.caseKey()).param("symbol", symbol)
            .param("marketAt", utc(marketAvailable)).param("newsAt", utc(newsAvailable))
            .param("decisionAt", utc(decisionAt))
            .param("marketHash", request.marketSourceFingerprint())
            .param("newsHash", request.newsSourceFingerprint())
            .param("inputHash", request.inputFingerprint())
            .param("fingerprint", caseFingerprint).param("recordedAt", utc(now))
            .param("actor", actor).update();
        for (EligibleCandidate candidate : candidates) {
            jdbc.sql("""
                    INSERT INTO paper_shadow_model_decision (
                        case_id, model_version_id, model_version_fingerprint,
                        role, action, predicted_at, paper_order_id, decision_fingerprint)
                    VALUES (:caseId, :versionId, :versionHash, :role, :action,
                        :predictedAt, :orderId, :fingerprint)
                    """)
                .param("caseId", caseId).param("versionId", candidate.versionId())
                .param("versionHash", candidate.versionFingerprint())
                .param("role", candidate.role().name()).param("action", candidate.action().name())
                .param("predictedAt", utc(candidate.predictedAt()))
                .param("orderId", candidate.paperOrderId(), java.sql.Types.OTHER)
                .param("fingerprint", candidate.fingerprint()).update();
        }
        audit.record("PAPER_SHADOW_EVIDENCE_CAPTURED", "PAPER_SHADOW_CASE", caseId.toString(),
            Map.of("caseKey", request.caseKey(), "caseFingerprint", caseFingerprint,
                "modelCount", candidates.size(), "hasExistingPaperOrder", !linkedOrders.isEmpty(),
                "capitalAuthority", false, "sourceCustodyVerified", false));
        return byId(caseId);
    }

    @Transactional(readOnly = true)
    public CaseEvidence byId(UUID id) {
        Objects.requireNonNull(id, "case ID required");
        var header = jdbc.sql("""
                SELECT case_id, case_key, symbol, market_available_at, news_available_at,
                       decision_at, market_source_fingerprint, news_source_fingerprint,
                       input_fingerprint, case_fingerprint, recorded_at, recorded_by
                  FROM paper_shadow_decision_case WHERE case_id = :id
                """)
            .param("id", id)
            .query((rs, rowNum) -> new CaseHeader(
                rs.getObject("case_id", UUID.class), rs.getString("case_key"),
                rs.getString("symbol"), time(rs, "market_available_at"),
                time(rs, "news_available_at"), time(rs, "decision_at"),
                rs.getString("market_source_fingerprint"), rs.getString("news_source_fingerprint"),
                rs.getString("input_fingerprint"), rs.getString("case_fingerprint"),
                time(rs, "recorded_at"), rs.getString("recorded_by")))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown PAPER/SHADOW case: " + id));
        List<CandidateEvidence> candidates = jdbc.sql("""
                SELECT d.model_version_id, d.model_version_fingerprint, d.role, d.action,
                       d.predicted_at, d.paper_order_id, o.status AS paper_order_status,
                       o.filled_quantity, COALESCE((
                           SELECT SUM(f.fee_amount) FROM paper_fill f WHERE f.order_id = o.id
                       ), 0) AS paper_fee_amount,
                       COALESCE((
                           SELECT SUM(f.slippage_amount) FROM paper_fill f WHERE f.order_id = o.id
                       ), 0) AS paper_slippage_amount, d.decision_fingerprint
                  FROM paper_shadow_model_decision d
                  LEFT JOIN paper_order o ON o.id = d.paper_order_id
                 WHERE d.case_id = :id
                 ORDER BY d.model_version_id
                """)
            .param("id", id)
            .query((rs, rowNum) -> new CandidateEvidence(
                rs.getObject("model_version_id", UUID.class),
                rs.getString("model_version_fingerprint"),
                CandidateRole.valueOf(rs.getString("role")),
                CandidateAction.valueOf(rs.getString("action")),
                time(rs, "predicted_at"), rs.getObject("paper_order_id", UUID.class),
                rs.getString("paper_order_status"),
                (Integer) rs.getObject("filled_quantity"),
                rs.getBigDecimal("paper_fee_amount"), rs.getBigDecimal("paper_slippage_amount"),
                rs.getString("decision_fingerprint")))
            .list();
        return new CaseEvidence(header.id(), CONTRACT, header.key(), header.symbol(),
            header.marketAvailableAt(), header.newsAvailableAt(), header.decisionAt(),
            header.marketHash(), header.newsHash(), header.inputHash(),
            header.fingerprint(), header.recordedAt(), header.actor(), candidates,
            false, false, false, false);
    }

    @Transactional(readOnly = true)
    public List<CaseEvidence> recent(int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be 1..50");
        List<UUID> ids = jdbc.sql("""
                SELECT case_id FROM paper_shadow_decision_case
                 ORDER BY recorded_at DESC, case_id DESC LIMIT :limit
                """).param("limit", limit).query(UUID.class).list();
        return ids.stream().map(this::byId).toList();
    }

    private void requireExistingPaperOrder(UUID orderId, String symbol, Instant decisionAt) {
        var order = jdbc.sql("""
                SELECT symbol, execution_origin, created_at FROM paper_order WHERE id = :id
                """)
            .param("id", orderId)
            .query((rs, rowNum) -> new LinkedOrder(
                rs.getString("symbol"), rs.getString("execution_origin"),
                time(rs, "created_at")))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown PAPER order: " + orderId));
        if (!symbol.equalsIgnoreCase(order.symbol()) || !"AUTOMATED".equals(order.origin())
                || order.createdAt().isBefore(decisionAt)) {
            throw new IllegalArgumentException(
                "Linked order must be an existing AUTOMATED PAPER order after the decision for the same symbol");
        }
    }

    private static void requireHash(String hash, String label) {
        if (hash == null || !HASH.matcher(hash).matches()) {
            throw new IllegalArgumentException(label + " fingerprint must be 64 lowercase hex digits");
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant time(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        return rs.getObject(name, OffsetDateTime.class).toInstant();
    }

    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return "SYSTEM";
        }
        String name = auth.getName();
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalStateException("PAPER/SHADOW actor identity must be bounded");
        }
        return name;
    }

    private record ExistingCase(UUID id, String fingerprint) {}
    private record LinkedOrder(String symbol, String origin, Instant createdAt) {}
    private record CaseHeader(UUID id, String key, String symbol, Instant marketAvailableAt,
                              Instant newsAvailableAt, Instant decisionAt, String marketHash,
                              String newsHash, String inputHash, String fingerprint,
                              Instant recordedAt, String actor) {}
}
