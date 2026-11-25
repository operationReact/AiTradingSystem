package in.aitradingsystem.risk;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class RiskSharedApiContractIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void resumePaperOrders() {
        jdbc.update("UPDATE trading_control SET new_orders_halted = FALSE, reason = 'test cleanup', updated_at = now() WHERE id = 1");
    }

    @Test
    void canonicalPolicyEndpointRequiresOperatorAndReturnsPersistedActivePolicy() throws Exception {
        mvc.perform(get("/api/risk/policy"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/risk/policy").header(KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.version").isNumber())
                .andExpect(jsonPath("$.policyName").isNotEmpty())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.snapshot.riskPerTradePercent").isNumber())
                .andExpect(jsonPath("$.snapshot.maxOrderNotional").isNumber())
                .andExpect(jsonPath("$.snapshot.feeSlippageProfileVersion").isNotEmpty())
                .andExpect(jsonPath("$.effectiveFrom").isNotEmpty())
                .andExpect(jsonPath("$.createdBy").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void emergencyStopRequiresMeaningfulHaltReasonAndRepeatedStateIsSafe() throws Exception {
        mvc.perform(put("/api/risk/emergency-stop")
                        .header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"halted\":true}"))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/risk/emergency-stop")
                        .header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"halted\":true,\"reason\":\"ab\"}"))
                .andExpect(status().isBadRequest());

        String body = "{\"halted\":true,\"reason\":\"Risk feed anomaly\"}";
        mvc.perform(put("/api/risk/emergency-stop")
                        .header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newOrdersHalted").value(true))
                .andExpect(jsonPath("$.reason").value("Risk feed anomaly"));

        mvc.perform(put("/api/risk/emergency-stop")
                        .header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newOrdersHalted").value(true));
    }

    @Test
    void emergencyStopRejectsOversizedReasonAtValidationBoundary() throws Exception {
        String reason = "x".repeat(201);
        mvc.perform(put("/api/risk/emergency-stop")
                        .header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"halted\":true,\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
