package in.aitradingsystem.risk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.common.PageResponse;
import in.aitradingsystem.paper.PaperExecutionModel;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskDecisionPersistenceService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RiskDecisionPersistenceService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void persist(UUID orderId, RiskContext context, RiskDecisionV2 decision) {
        if (orderId == null || context == null || decision == null) {
            throw new IllegalArgumentException("orderId, context and decision are required");
        }
        if (!orderId.equals(context.orderId())) {
            throw new IllegalArgumentException("orderId must match RiskContext.orderId");
        }
        if (!decision.policyConfigId().equals(context.policy().id())
                || decision.policyVersion() != context.policy().version()) {
            throw new IllegalArgumentException("decision policy provenance does not match RiskContext");
        }

        List<String> reasonCodes = decision.ruleResults().stream()
                .filter(r -> r.outcome() != RiskOutcome.PASS)
                .map(RiskRuleResult::reasonCode)
                .toList();
        BigDecimal dailyLossLimit = PaperExecutionModel.money(context.startingCapital()
                .multiply(context.policy().maxDailyDrawdownPercent()).divide(HUNDRED));
        String legacyDecision = decision.decisionClass() == RiskDecisionClass.APPROVED ? "APPROVED" : "REJECTED";

        int inserted = jdbc.update("""
                INSERT INTO risk_decision (
                    id, order_id, decision, reason_codes, risk_budget, estimated_risk,
                    estimated_notional, available_cash, open_positions, realized_pnl_today,
                    daily_loss_limit, policy_version, evaluated_at,
                    policy_config_id, result_class, policy_snapshot, reward_risk_ratio,
                    gross_exposure_after, symbol_exposure_after, trades_today_after, correlation_id)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
                """,
                decision.decisionId(), orderId, legacyDecision, json(reasonCodes),
                decision.riskBudget(), decision.estimatedRisk(), decision.grossNotional(),
                context.availableCash(), context.openPositionCount(), context.realizedPnlToday(),
                dailyLossLimit, Integer.toString(decision.policyVersion()), java.sql.Timestamp.from(decision.evaluatedAt()),
                decision.policyConfigId(), decision.decisionClass().name(), json(context.policy()),
                decision.rewardRiskRatio(), decision.grossExposureAfter(), decision.symbolExposureAfter(),
                decision.tradesTodayAfter(), context.correlationId());
        if (inserted != 1) throw new IllegalStateException("risk decision insert failed");

        for (RiskRuleResult result : decision.ruleResults()) {
            jdbc.update("""
                    INSERT INTO risk_decision_rule (
                        risk_decision_id, rule_sequence, rule_code, outcome, reason_code,
                        observed_value, limit_value, unit, details)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """,
                    decision.decisionId(), sequence(result.code()), result.code().name(), result.outcome().name(),
                    result.reasonCode(), result.observedValue(), result.limitValue(), result.unit(), json(result.details()));
        }
    }

    public RiskDecisionDetail require(UUID id) {
        if (id == null) throw new IllegalArgumentException("risk decision id is required");
        RiskDecisionDetailBase base = jdbc.query("""
                SELECT rd.*,
                       (SELECT COUNT(*) FROM risk_decision_rule rr
                         WHERE rr.risk_decision_id = rd.id AND rr.outcome <> 'PASS') AS failed_rule_count
                  FROM risk_decision rd
                 WHERE rd.id = ?
                """, (rs, row) -> mapBase(rs), id).stream().findFirst()
                .orElseThrow(() -> new RiskApiException(
                        RiskErrorCode.RISK_DECISION_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Risk decision not found"));

        List<RiskRuleResult> rules = jdbc.query("""
                SELECT rule_code, outcome, reason_code, observed_value, limit_value, unit, details
                  FROM risk_decision_rule
                 WHERE risk_decision_id = ?
                 ORDER BY rule_sequence, rule_code
                """, (rs, row) -> new RiskRuleResult(
                RiskRuleCode.valueOf(rs.getString("rule_code")),
                RiskOutcome.valueOf(rs.getString("outcome")),
                rs.getString("reason_code"),
                rs.getBigDecimal("observed_value"),
                rs.getBigDecimal("limit_value"),
                rs.getString("unit"),
                readMap(rs.getString("details"))), id);

        return new RiskDecisionDetail(base.summary(), base.estimatedNotional(), base.policySnapshot(), rules);
    }

    public PageResponse<RiskDecisionSummary> list(int page, int size) {
        if (page < 0) throw new IllegalArgumentException("page must be >= 0");
        if (size < 1 || size > 100) throw new IllegalArgumentException("size must be between 1 and 100");
        List<RiskDecisionSummary> items = jdbc.query("""
                SELECT rd.*,
                       (SELECT COUNT(*) FROM risk_decision_rule rr
                         WHERE rr.risk_decision_id = rd.id AND rr.outcome <> 'PASS') AS failed_rule_count
                  FROM risk_decision rd
                 ORDER BY rd.evaluated_at DESC, rd.id DESC
                 LIMIT ? OFFSET ?
                """, (rs, row) -> mapBase(rs).summary(), size, page * size);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM risk_decision", Long.class);
        return PageResponse.of(items, page, size, total == null ? 0 : total);
    }

    private RiskDecisionDetailBase mapBase(ResultSet rs) throws SQLException {
        String resultClassValue = rs.getString("result_class");
        RiskDecisionClass resultClass = resultClassValue == null
                ? ("APPROVED".equals(rs.getString("decision")) ? RiskDecisionClass.APPROVED : RiskDecisionClass.REJECTED)
                : RiskDecisionClass.valueOf(resultClassValue);
        UUID policyConfigId = rs.getObject("policy_config_id", UUID.class);
        String policyVersion = rs.getString("policy_version");
        RiskPolicySnapshot snapshot = readPolicySnapshot(rs.getString("policy_snapshot"));
        List<String> reasonCodes = readStringList(rs.getString("reason_codes"));
        OffsetDateTime evaluatedAt = rs.getObject("evaluated_at", OffsetDateTime.class);
        RiskDecisionSummary summary = new RiskDecisionSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getString("decision"),
                reasonCodes,
                rs.getBigDecimal("risk_budget"),
                rs.getBigDecimal("estimated_risk"),
                rs.getBigDecimal("available_cash"),
                rs.getInt("open_positions"),
                rs.getBigDecimal("realized_pnl_today"),
                rs.getBigDecimal("daily_loss_limit"),
                policyVersion,
                evaluatedAt.toInstant(),
                resultClass,
                policyConfigId,
                rs.getBigDecimal("reward_risk_ratio"),
                rs.getBigDecimal("gross_exposure_after"),
                rs.getBigDecimal("symbol_exposure_after"),
                rs.getObject("trades_today_after", Integer.class),
                rs.getLong("failed_rule_count"),
                rs.getString("correlation_id"));
        return new RiskDecisionDetailBase(summary, rs.getBigDecimal("estimated_notional"), snapshot);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("risk decision JSON serialization failed", ex);
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored reason_codes JSON is invalid", ex);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored risk rule details JSON is invalid", ex);
        }
    }

    private RiskPolicySnapshot readPolicySnapshot(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.hasNonNull("id")) return null;
            return objectMapper.treeToValue(node, RiskPolicySnapshot.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored policy_snapshot JSON is invalid", ex);
        }
    }

    private static int sequence(RiskRuleCode code) {
        return switch (code) {
            case EMERGENCY_STOP -> 10;
            case INSTRUMENT_ELIGIBILITY -> 20;
            case SIGNAL_TIMESTAMP -> 30;
            case SIGNAL_FRESHNESS -> 40;
            case MARKET_STATE_FRESHNESS -> 50;
            case MARKET_SESSION -> 60;
            case PRICE_LEVELS -> 70;
            case MIN_REWARD_RISK -> 80;
            case DAILY_LOSS_LIMIT -> 90;
            case PER_TRADE_RISK_BUDGET -> 100;
            case MAX_ORDER_NOTIONAL -> 110;
            case CASH_AVAILABILITY, HOLDINGS_AVAILABILITY -> 120;
            case EXISTING_SYMBOL_POSITION -> 130;
            case MAX_OPEN_POSITIONS -> 140;
            case MAX_GROSS_EXPOSURE -> 150;
            case MAX_SYMBOL_EXPOSURE -> 160;
            case MAX_TRADES_PER_DAY -> 170;
            case DUPLICATE_PENDING_EXPOSURE -> 180;
        };
    }

    private record RiskDecisionDetailBase(
            RiskDecisionSummary summary,
            BigDecimal estimatedNotional,
            RiskPolicySnapshot policySnapshot) {
    }
}
