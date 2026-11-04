package in.aitradingsystem.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.domain.Side;
import in.aitradingsystem.governance.ModelRegistryService;
import in.aitradingsystem.paper.PaperFillRepository.FillWrite;
import in.aitradingsystem.paper.PaperShadowDecisionEvidenceService.CandidateAction;
import in.aitradingsystem.paper.PaperShadowDecisionEvidenceService.CandidateCapture;
import in.aitradingsystem.paper.PaperShadowDecisionEvidenceService.CandidateRole;
import in.aitradingsystem.paper.PaperShadowDecisionEvidenceService.CaseCapture;
import in.aitradingsystem.paper.PaperShadowDecisionEvidenceService.CaseEvidence;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

class PaperShadowDecisionEvidenceServiceIT extends AbstractPostgresIT {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final String D = "d".repeat(64);
    private static final Instant MARKET = Instant.parse("2026-10-29T04:00:00Z");
    private static final Instant NEWS = MARKET.plusSeconds(10);
    private static final Instant DECISION = MARKET.plusSeconds(60);

    @Autowired PaperShadowDecisionEvidenceService evidence;
    @Autowired ModelRegistryService registry;
    @Autowired PaperFillRepository fills;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    private final List<UUID> definitions = new ArrayList<>();
    private final List<UUID> versions = new ArrayList<>();
    private final List<UUID> cases = new ArrayList<>();
    private final List<UUID> orders = new ArrayList<>();
    private UUID champion;
    private UUID challenger;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "v4-shadow-test-admin", "N/A", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        champion = model("champion");
        challenger = model("challenger");
    }

    private UUID model(String suffix) {
        String key = "v4shadow-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
        var definition = registry.createDefinition(new ModelRegistryService.DefinitionSpec(
            key, suffix + " model", "CATBOOST", "ALPHA", "LOCAL", "RESEARCH_ONLY", null));
        var version = registry.createVersion(key, new ModelRegistryService.VersionSpec(
            "v1", A, B, C, D, "0123456789abcdef0123456789abcdef01234567", null, MARKET.minusSeconds(3600)));
        definitions.add(definition.definitionId());
        versions.add(version.versionId());
        return version.versionId();
    }

    @AfterEach
    void cleanUp() {
        for (UUID id : cases) {
            jdbc.sql("DELETE FROM paper_shadow_model_decision WHERE case_id = :id")
                .param("id", id).update();
            jdbc.sql("DELETE FROM paper_shadow_decision_case WHERE case_id = :id")
                .param("id", id).update();
        }
        for (UUID id : orders) {
            jdbc.sql("DELETE FROM paper_fill WHERE order_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM paper_order WHERE id = :id").param("id", id).update();
        }
        for (UUID id : versions) {
            jdbc.sql("DELETE FROM model_version WHERE version_id = :id").param("id", id).update();
        }
        for (UUID id : definitions) {
            jdbc.sql("DELETE FROM model_definition WHERE definition_id = :id").param("id", id).update();
        }
        SecurityContextHolder.clearContext();
    }

    private CaseCapture request(String key, UUID linked) {
        return new CaseCapture(key, "TESTEQ", MARKET, NEWS, DECISION, A, B, C,
            List.of(
                new CandidateCapture(champion, CandidateRole.PAPER_CHAMPION,
                    CandidateAction.ENTER, DECISION, linked),
                new CandidateCapture(challenger, CandidateRole.SHADOW_CHALLENGER,
                    CandidateAction.WAIT, DECISION, null)));
    }

    private String key() {
        return "eval-" + UUID.randomUUID();
    }

    private CaseEvidence capture(CaseCapture request) {
        CaseEvidence result = evidence.capture(request);
        cases.add(result.caseId());
        return result;
    }

    private UUID seedPaperOrder(String symbol, String origin, Instant createdAt) {
        UUID id = UUID.randomUUID();
        orders.add(id);
        jdbc.sql("""
                INSERT INTO paper_order (
                    id, idempotency_key, request_hash, symbol, side, quantity,
                    submitted_quantity, filled_quantity, reference_price,
                    stop_loss, target_price, status, execution_origin, created_at, updated_at)
                VALUES (:id, :key, :hash, :symbol, 'BUY', 3, 3, 3, 100.00,
                    90.00, 110.00, 'FILLED', :origin, :createdAt, :createdAt)
                """)
            .param("id", id).param("key", "v4-shadow-order-" + id)
            .param("hash", A).param("symbol", symbol).param("origin", origin)
            .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
            .update();
        return id;
    }

    @Test
    void immutableDecisionsCanLinkExistingPaperOrderAndActualFillCosts() {
        UUID orderId = seedPaperOrder("TESTEQ", "AUTOMATED", DECISION.plusSeconds(2));
        fills.appendFill(orderId, 1, new FillWrite(
            "TESTEQ", Side.BUY, 3, new BigDecimal("100.00"),
            new BigDecimal("100.05"), new BigDecimal("300.15"),
            new BigDecimal("1.15"), new BigDecimal("0.15"),
            new BigDecimal("-301.30"), new BigDecimal("301.30"), BigDecimal.ZERO,
            "PAPER_EXECUTION_V1", "FULL_LIQUIDITY", DECISION.plusSeconds(3)));
        CaseCapture input = request(key(), orderId);
        CaseEvidence recorded = capture(input);

        assertThat(recorded.contractVersion()).isEqualTo("paper-shadow-decision-evidence.v1");
        assertThat(recorded.candidates()).hasSize(2);
        var leader = recorded.candidates().stream()
            .filter(c -> c.role() == CandidateRole.PAPER_CHAMPION).findFirst().orElseThrow();
        var challengerRow = recorded.candidates().stream()
            .filter(c -> c.role() == CandidateRole.SHADOW_CHALLENGER).findFirst().orElseThrow();
        assertThat(leader.paperOrderId()).isEqualTo(orderId);
        assertThat(leader.paperOrderStatus()).isEqualTo("FILLED");
        assertThat(leader.filledQuantity()).isEqualTo(3);
        assertThat(leader.paperFeeAmount()).isEqualByComparingTo("1.15");
        assertThat(leader.paperSlippageAmount()).isEqualByComparingTo("0.15");
        assertThat(challengerRow.paperOrderId()).isNull();
        assertThat(challengerRow.paperOrderStatus()).isNull();
        assertThat(recorded.sourceCustodyIndependentlyVerified()).isFalse();
        assertThat(recorded.realRuntimeSoakVerified()).isFalse();
        assertThat(recorded.liveOrdersAllowed()).isFalse();
        assertThat(recorded.canAuthorizeCapital()).isFalse();

        assertThat(evidence.byId(recorded.caseId())).isEqualTo(recorded);
        assertThat(evidence.capture(input).caseId()).isEqualTo(recorded.caseId());
        Integer count = jdbc.sql("SELECT count(*) FROM paper_shadow_decision_case WHERE case_key = :key")
            .param("key", input.caseKey()).query(Integer.class).single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void conflictingReplayCannotChangeFrozenCaseEvidence() {
        CaseCapture input = request(key(), null);
        capture(input);
        CaseCapture changed = new CaseCapture(input.caseKey(), input.symbol(),
            input.marketAvailableAt(), input.newsAvailableAt(), input.decisionAt(),
            D, B, C, input.candidates());
        assertThatThrownBy(() -> evidence.capture(changed))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Conflicting replay");
    }

    @Test
    void lateSourceAndLatePredictionCannotBeRecorded() {
        CaseCapture original = request(key(), null);
        CaseCapture lateMarket = new CaseCapture(original.caseKey(), original.symbol(),
            DECISION.plusSeconds(1), NEWS, DECISION, A, B, C, original.candidates());
        assertThatThrownBy(() -> evidence.capture(lateMarket))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("available by decision");
        CaseCapture lateModel = new CaseCapture(original.caseKey(), original.symbol(),
            MARKET, NEWS, DECISION, A, B, C,
            List.of(original.candidates().get(0),
                new CandidateCapture(challenger, CandidateRole.SHADOW_CHALLENGER,
                    CandidateAction.WAIT, DECISION.plusSeconds(1), null)));
        assertThatThrownBy(() -> evidence.capture(lateModel))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("causal decision window");
    }

    @Test
    void rejectsMissingOrDuplicateModelVersionsAndNoChampion() {
        CaseCapture original = request(key(), null);
        CaseCapture unknown = new CaseCapture(original.caseKey(), original.symbol(),
            MARKET, NEWS, DECISION, A, B, C,
            List.of(original.candidates().get(0),
                new CandidateCapture(UUID.randomUUID(), CandidateRole.SHADOW_CHALLENGER,
                    CandidateAction.WAIT, DECISION, null)));
        assertThatThrownBy(() -> evidence.capture(unknown))
            .isInstanceOf(java.util.NoSuchElementException.class)
            .hasMessageContaining("Unknown registered model version");
        CaseCapture duplicate = new CaseCapture(original.caseKey(), original.symbol(),
            MARKET, NEWS, DECISION, A, B, C,
            List.of(original.candidates().get(0),
                new CandidateCapture(champion, CandidateRole.SHADOW_CHALLENGER,
                    CandidateAction.WAIT, DECISION, null)));
        assertThatThrownBy(() -> evidence.capture(duplicate))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate model version");
        CaseCapture noChampion = new CaseCapture(original.caseKey(), original.symbol(),
            MARKET, NEWS, DECISION, A, B, C,
            List.of(
                new CandidateCapture(champion, CandidateRole.SHADOW_CHALLENGER,
                    CandidateAction.ENTER, DECISION, null),
                new CandidateCapture(challenger, CandidateRole.SHADOW_CHALLENGER,
                    CandidateAction.WAIT, DECISION, null)));
        assertThatThrownBy(() -> evidence.capture(noChampion))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Exactly one PAPER champion");
    }

    @Test
    void shadowCannotAttachBrokerOrPaperOrderAndManualCannotMasqueradeAsAutomated() {
        UUID orderId = seedPaperOrder("TESTEQ", "AUTOMATED", DECISION.plusSeconds(2));
        CaseCapture original = request(key(), orderId);
        CaseCapture attachedShadow = new CaseCapture(original.caseKey(), original.symbol(),
            MARKET, NEWS, DECISION, A, B, C,
            List.of(
                new CandidateCapture(champion, CandidateRole.PAPER_CHAMPION,
                    CandidateAction.ENTER, DECISION, null),
                new CandidateCapture(challenger, CandidateRole.SHADOW_CHALLENGER,
                    CandidateAction.ENTER, DECISION, orderId)));
        assertThatThrownBy(() -> evidence.capture(attachedShadow))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Only an ENTER PAPER champion");
        UUID manual = seedPaperOrder("TESTEQ", "MANUAL", DECISION.plusSeconds(2));
        assertThatThrownBy(() -> evidence.capture(request(key(), manual)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("existing AUTOMATED PAPER order");
        UUID wrongSymbol = seedPaperOrder("OTHER", "AUTOMATED", DECISION.plusSeconds(2));
        assertThatThrownBy(() -> evidence.capture(request(key(), wrongSymbol)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("same symbol");
        UUID early = seedPaperOrder("TESTEQ", "AUTOMATED", DECISION.minusSeconds(1));
        assertThatThrownBy(() -> evidence.capture(request(key(), early)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("after the decision");
    }

    @Test
    void oneOrderCannotBeLinkedToTwoDifferentModelDecisions() {
        UUID orderId = seedPaperOrder("TESTEQ", "AUTOMATED", DECISION.plusSeconds(2));
        capture(request(key(), orderId));
        assertThatThrownBy(() -> evidence.capture(request(key(), orderId)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void adminOnlyGetRoutesCannotBeReadByViewerTraderOrOperator() throws Exception {
        CaseEvidence recorded = capture(request(key(), null));
        for (String role : List.of("VIEWER", "TRADER", "OPERATOR")) {
            mvc.perform(get("/api/admin/model-governance/paper-shadow/evidence/cases")
                    .with(user(role.toLowerCase()).roles(role)))
                .andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/model-governance/paper-shadow/evidence/cases/" + recorded.caseId())
                    .with(user(role.toLowerCase()).roles(role)))
                .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/model-governance/paper-shadow/evidence/cases/" + recorded.caseId())
                .with(user("shadow-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.caseKey").value(recorded.caseKey()))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false))
            .andExpect(jsonPath("$.liveOrdersAllowed").value(false))
            .andExpect(jsonPath("$.candidates.length()").value(2));
    }
}
