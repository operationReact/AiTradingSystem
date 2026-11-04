package in.aitradingsystem.governance;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.execution.TradingExecutionAuthority;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One immutable reference from a V56 matched PAPER champion ENTER decision to an
 * existing Java PAPER order. Never submits orders, fabricates fills or promotes models.
 * Real fill totals are read directly from the authoritative PAPER ledger at query time.
 */
@Service
public class MatchedPaperOrderEvidenceService {
    public static final String CONTRACT = "v4-matched-paper-order-link.v1";

    private final JdbcClient jdbc;
    private final MatchedPaperShadowIntakeService intake;
    private final TradingExecutionAuthority authority;
    private final AuditService audit;
    private final Clock clock;

    public MatchedPaperOrderEvidenceService(
            JdbcClient jdbc, MatchedPaperShadowIntakeService intake,
            TradingExecutionAuthority authority, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.intake = intake;
        this.authority = authority;
        this.audit = audit;
        this.clock = clock;
    }

    public record LinkInput(UUID paperOrderId) {}

    public record LinkView(
        String contractVersion, UUID sessionId, String caseKey,
        UUID championModelVersionId, UUID paperOrderId, String decisionFingerprint,
        String linkFingerprint, Instant linkedAt, String linkedBy, String paperOrderStatus,
        int submittedQuantity, int paperOrderFilledQuantity, long recordedFillQuantity,
        BigDecimal recordedFeeAmount, BigDecimal recordedSlippageAmount,
        boolean fillQuantityReconciles, boolean independentlyVerifiedRuntime,
        boolean brokerOrderSubmitted, boolean canAuthorizeCapital) {}

    private record ExistingLink(UUID orderId, String fingerprint) {}
    private record ExistingPaperOrder(String symbol, String origin, String side, Instant createdAt) {}

    @Transactional
    public LinkView attach(UUID sessionId, String caseKey, LinkInput input) {
        if (!TradingExecutionAuthority.PAPER.equals(authority.current().tradingMode())) {
            throw new IllegalStateException("PAPER/SHADOW links can only be recorded in PAPER mode");
        }
        if (input == null || input.paperOrderId() == null) {
            throw new IllegalArgumentException("An existing PAPER order ID is required");
        }
        var matchedCase = intake.observation(sessionId, caseKey);
        var champions = matchedCase.decisions().stream()
            .filter(d -> d.role() == MatchedPaperShadowIntakeService.Role.PAPER_CHAMPION)
            .toList();
        if (champions.size() != 1
                || champions.getFirst().action() != MatchedPaperShadowIntakeService.Action.ENTER) {
            throw new IllegalArgumentException("A single PAPER champion ENTER decision is required");
        }
        var champion = champions.getFirst();
        ExistingPaperOrder order = jdbc.sql("""
                SELECT symbol, execution_origin, side, created_at FROM paper_order WHERE id = :orderId
                """)
            .param("orderId", input.paperOrderId())
            .query((rs, row) -> new ExistingPaperOrder(
                rs.getString("symbol"), rs.getString("execution_origin"),
                rs.getString("side"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Unknown existing PAPER order"));
        if (!matchedCase.symbol().equalsIgnoreCase(order.symbol())
                || !"AUTOMATED".equals(order.origin())
                || !"BUY".equals(order.side())
                || order.createdAt().isBefore(matchedCase.decisionAt())) {
            throw new IllegalArgumentException(
                "Order must be an existing automated PAPER BUY for the same symbol after the decision");
        }
        String fingerprint = ModelRegistryService.fingerprint(
            CONTRACT, sessionId.toString(), caseKey,
            matchedCase.caseFingerprint(), champion.modelVersionId().toString(),
            champion.decisionFingerprint(), input.paperOrderId().toString());
        var previous = jdbc.sql("""
                SELECT paper_order_id, link_fingerprint
                  FROM v4_matched_paper_order_link
                 WHERE session_id = :sessionId AND case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query((rs, row) -> new ExistingLink(
                rs.getObject("paper_order_id", UUID.class), rs.getString("link_fingerprint")))
            .optional();
        if (previous.isPresent()) {
            if (!previous.get().orderId().equals(input.paperOrderId())
                    || !previous.get().fingerprint().equals(fingerprint)) {
                throw new IllegalStateException("Conflicting immutable PAPER order evidence link");
            }
            return byCase(sessionId, caseKey);
        }

        Instant now = clock.instant();
        String actor = actor();
        jdbc.sql("""
                INSERT INTO v4_matched_paper_order_link (
                    session_id, case_key, model_version_id, paper_order_id,
                    decision_fingerprint, link_fingerprint, linked_at, linked_by)
                VALUES (:sessionId, :caseKey, :modelId, :orderId,
                    :decisionHash, :linkHash, :linkedAt, :linkedBy)
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .param("modelId", champion.modelVersionId())
            .param("orderId", input.paperOrderId())
            .param("decisionHash", champion.decisionFingerprint())
            .param("linkHash", fingerprint)
            .param("linkedAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("linkedBy", actor)
            .update();
        audit.record("V4_PAPER_SHADOW_PAPER_LINK_CAPTURED", "V4_PAPER_SHADOW_CASE",
            sessionId + ":" + caseKey, Map.of(
                "sessionId", sessionId.toString(), "caseKey", caseKey,
                "paperOrderId", input.paperOrderId().toString(),
                "linkFingerprint", fingerprint, "canAuthorizeCapital", false));
        return byCase(sessionId, caseKey);
    }

    @Transactional(readOnly = true)
    public LinkView byCase(UUID sessionId, String caseKey) {
        return jdbc.sql("""
                SELECT l.session_id, l.case_key, l.model_version_id,
                       l.paper_order_id, l.decision_fingerprint, l.link_fingerprint,
                       l.linked_at, l.linked_by, o.status, o.submitted_quantity,
                       o.filled_quantity,
                       (SELECT COALESCE(SUM(f.quantity),0) FROM paper_fill f
                         WHERE f.order_id = l.paper_order_id) AS recorded_fill_quantity,
                       (SELECT COALESCE(SUM(f.fee_amount),0) FROM paper_fill f
                         WHERE f.order_id = l.paper_order_id) AS recorded_fee_amount,
                       (SELECT COALESCE(SUM(f.slippage_amount),0) FROM paper_fill f
                         WHERE f.order_id = l.paper_order_id) AS recorded_slippage_amount
                  FROM v4_matched_paper_order_link l
                  JOIN paper_order o ON o.id = l.paper_order_id
                 WHERE l.session_id = :sessionId AND l.case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query((rs, row) -> {
                long fills = rs.getLong("recorded_fill_quantity");
                int filled = rs.getInt("filled_quantity");
                return new LinkView(
                    CONTRACT, rs.getObject("session_id", UUID.class),
                    rs.getString("case_key"), rs.getObject("model_version_id", UUID.class),
                    rs.getObject("paper_order_id", UUID.class),
                    rs.getString("decision_fingerprint"), rs.getString("link_fingerprint"),
                    rs.getObject("linked_at", OffsetDateTime.class).toInstant(),
                    rs.getString("linked_by"), rs.getString("status"),
                    rs.getInt("submitted_quantity"), filled, fills,
                    rs.getBigDecimal("recorded_fee_amount"), rs.getBigDecimal("recorded_slippage_amount"),
                    fills == filled, false, false, false);
            })
            .optional()
            .orElseThrow(() -> new NoSuchElementException("No PAPER order evidence linked to this case"));
    }

    private static String actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken
                || auth.getName() == null || auth.getName().isBlank()
                || auth.getName().length() > 120) {
            throw new IllegalStateException("Authenticated actor required for PAPER evidence linkage");
        }
        return auth.getName();
    }
}
