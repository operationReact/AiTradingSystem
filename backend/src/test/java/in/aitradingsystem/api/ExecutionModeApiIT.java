package in.aitradingsystem.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class ExecutionModeApiIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void resetModes() {
        jdbc.sql("UPDATE trading_execution_mode SET active_mode = 'PAPER', order_execution_mode = 'MANUAL', updated_by = 'test' WHERE id = 1")
            .update();
    }

    @Test
    void requiresApiKey() throws Exception {
        mvc.perform(get("/api/system/execution-mode")).andExpect(status().isUnauthorized());
    }

    @Test
    void reportsSafeDefaults() throws Exception {
        mvc.perform(get("/api/system/execution-mode").header(KEY_HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeMode").value("PAPER"))
            .andExpect(jsonPath("$.orderExecutionMode").value("MANUAL"))
            .andExpect(jsonPath("$.liveAvailable").value(false))
            .andExpect(jsonPath("$.liveBlockers").isArray());
    }

    @Test
    void automatedModeCanBeSelectedWithoutChangingPaperTradingMode() throws Exception {
        mvc.perform(put("/api/system/execution-mode/order-execution").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"AUTOMATED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeMode").value("PAPER"))
            .andExpect(jsonPath("$.orderExecutionMode").value("AUTOMATED"));

        mvc.perform(get("/api/system/execution-mode").header(KEY_HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeMode").value("PAPER"))
            .andExpect(jsonPath("$.orderExecutionMode").value("AUTOMATED"));
    }

    @Test
    void executionModeSwitchIsIdempotentAndCanReturnToManual() throws Exception {
        mvc.perform(put("/api/system/execution-mode/order-execution").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"MANUAL\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderExecutionMode").value("MANUAL"));
    }

    @Test
    void invalidOrderExecutionModeIsRejected() throws Exception {
        mvc.perform(put("/api/system/execution-mode/order-execution").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ROBOT\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void liveSwitchWithoutConfirmationIsRejected() throws Exception {
        mvc.perform(put("/api/system/execution-mode").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"LIVE\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void liveSwitchWithConfirmationIsRefusedAndModesRemainSafe() throws Exception {
        mvc.perform(put("/api/system/execution-mode/order-execution").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"AUTOMATED\"}"))
            .andExpect(status().isOk());

        mvc.perform(put("/api/system/execution-mode").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"LIVE\",\"confirmation\":\"SWITCH TO LIVE\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.activeMode").value("PAPER"))
            .andExpect(jsonPath("$.liveAvailable").value(false));

        mvc.perform(get("/api/system/execution-mode").header(KEY_HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(jsonPath("$.activeMode").value("PAPER"))
            .andExpect(jsonPath("$.orderExecutionMode").value("AUTOMATED"))
            .andExpect(jsonPath("$.liveAvailable").value(false));
    }

    @Test
    void paperRequestIsIdempotentAndDoesNotChangeOrderExecutionMode() throws Exception {
        mvc.perform(put("/api/system/execution-mode/order-execution").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"AUTOMATED\"}"))
            .andExpect(status().isOk());

        mvc.perform(put("/api/system/execution-mode").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"PAPER\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeMode").value("PAPER"))
            .andExpect(jsonPath("$.orderExecutionMode").value("AUTOMATED"));
    }

    @Test
    void invalidTradingModeIsRejected() throws Exception {
        mvc.perform(put("/api/system/execution-mode").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"AUTO\"}"))
            .andExpect(status().isBadRequest());
    }
}
