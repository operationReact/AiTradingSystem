package in.aitradingsystem.risk;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Canonical Phase 5 API contract matrix. */
class RiskApiIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";
    private static final String MASTER = """
            [{"token":"99111","symbol":"RISKAPI-EQ","name":"RISK API TEST","expiry":"","strike":"-1.000000","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5.000000"}]
            """;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired InstrumentMasterService instruments;
    @Autowired RiskPolicyConfigRepository policies;

    @AfterEach
    void resetEmergencyStop() {
        jdbc.update("UPDATE trading_control SET new_orders_halted = FALSE, reason = 'risk api cleanup', updated_at = now() WHERE id = 1");
    }

    @Test
    void activePolicyHistoryValidationAndNewVersionAreBoundedAndServerVersioned() throws Exception {
        String activeBody = mvc.perform(get("/api/risk/policy").header(KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();
        int previousVersion = json.readTree(activeBody).path("version").asInt();

        mvc.perform(get("/api/risk/policies").header(KEY_HEADER, API_KEY).param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));
        mvc.perform(get("/api/risk/policies").header(KEY_HEADER, API_KEY).param("page", "0").param("size", "101"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/risk/policies").header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(validPolicyJson("Risk API vNext")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(previousVersion + 1))
                .andExpect(jsonPath("$.active").value(true));

        String invalid = validPolicyJson("Invalid").replace("\"riskPerTradePercent\":0.25", "\"riskPerTradePercent\":2.00")
                .replace("\"maxDailyDrawdownPercent\":1.00", "\"maxDailyDrawdownPercent\":1.50");
        mvc.perform(post("/api/risk/policies").header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
    }

    @Test
    void decisionDetailReturnsPersistedOrderedRuleEvidence() throws Exception {
        RiskPolicySnapshot policy = policies.requireActive();
        UUID orderId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO paper_order(id,idempotency_key,request_hash,symbol,side,quantity,reference_price,stop_loss,target_price,
                  signal_generated_at,status,created_at,updated_at)
                VALUES (?,?,?,'RISKAPI','BUY',1,100,95,110,now(),'REJECTED',now(),now())
                """, orderId, "risk-api-" + orderId, "a".repeat(64));
        jdbc.update("""
                INSERT INTO risk_decision(id,order_id,decision,reason_codes,risk_budget,estimated_risk,estimated_notional,
                  available_cash,open_positions,realized_pnl_today,daily_loss_limit,policy_version,evaluated_at,
                  policy_config_id,result_class,policy_snapshot,reward_risk_ratio,gross_exposure_after,
                  symbol_exposure_after,trades_today_after,correlation_id)
                VALUES (?,?, 'REJECTED','[\"MAX_ORDER_NOTIONAL\"]'::jsonb,400,500,100,80000,0,0,1600,?,now(),?,
                  'REJECTED',?::jsonb,2.0,100,100,1,'risk-api-it')
                """, decisionId, orderId, Integer.toString(policy.version()), policy.id(), policyJson(policy));
        jdbc.update("""
                INSERT INTO risk_decision_rule(risk_decision_id,rule_sequence,rule_code,outcome,reason_code,observed_value,limit_value,unit,details)
                VALUES (?,1,'MAX_ORDER_NOTIONAL','HARD_REJECT','MAX_ORDER_NOTIONAL',100,50,'INR','{}'::jsonb)
                """, decisionId);

        mvc.perform(get("/api/risk/decisions/{id}", decisionId).header(KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.id").value(decisionId.toString()))
                .andExpect(jsonPath("$.summary.resultClass").value("REJECTED"))
                .andExpect(jsonPath("$.ruleResults[0].code").value("MAX_ORDER_NOTIONAL"))
                .andExpect(jsonPath("$.ruleResults[0].outcome").value("HARD_REJECT"));
    }

    @Test
    void positionSizingUsesServerOwnedState() throws Exception {
        jdbc.update("DELETE FROM instrument_master");
        jdbc.update("UPDATE instrument_catalogue_state SET active_version = NULL, activated_at = NULL WHERE singleton_id = 1");
        jdbc.update("DELETE FROM instrument_import_run");
        instruments.importSource(MASTER.getBytes(StandardCharsets.UTF_8));
        Long instrumentId = jdbc.queryForObject(
                "SELECT instrument_id FROM instrument_master WHERE trading_symbol = 'RISKAPI-EQ' AND active", Long.class);

        mvc.perform(post("/api/risk/position-size").header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":%d,"exchange":"NSE","symbol":"RISKAPI-EQ","entryPrice":100,
                                 "stopLoss":95,"targetPrice":110,"strategy":"RISK_API_IT","tradingStyle":"INTRADAY",
                                 "availableCash":999999999,"policyVersion":999}
                                """.formatted(instrumentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").isNumber())
                .andExpect(jsonPath("$.policyVersion").value(policies.requireActive().version()));
    }

    @Test
    void emergencyStopRequiresReasonAndIsIdempotent() throws Exception {
        mvc.perform(put("/api/risk/emergency-stop").header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"halted\":true}"))
                .andExpect(status().isBadRequest());

        String halt = "{\"halted\":true,\"reason\":\"Risk API test\"}";
        mvc.perform(put("/api/risk/emergency-stop").header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(halt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.newOrdersHalted").value(true));
        mvc.perform(put("/api/risk/emergency-stop").header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(halt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.newOrdersHalted").value(true));
    }

    private String policyJson(RiskPolicySnapshot policy) throws Exception {
        return json.writeValueAsString(policy);
    }

    private static String validPolicyJson(String name) {
        return """
                {"policyName":"%s","riskPerTradePercent":0.25,"maxDailyDrawdownPercent":1.00,
                 "maxOpenPositions":5,"minRewardRiskRatio":1.50,"maxOrderNotional":100000.00,
                 "maxGrossExposurePercent":80.00,"maxSymbolExposurePercent":25.00,"maxTradesPerDay":20,
                 "signalMaxAgeMinutes":15,"marketStateMaxAgeSeconds":60,"enforceMarketSession":false,
                 "rejectExistingSymbolPosition":true,"rejectDuplicatePendingExposure":true,
                 "feeRate":0.00050000,"slippageRate":0.00100000,"feeSlippageProfileVersion":"risk-api-it"}
                """.formatted(name);
    }
}
