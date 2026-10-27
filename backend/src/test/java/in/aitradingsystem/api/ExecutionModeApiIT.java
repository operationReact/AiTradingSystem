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
    void resetModeToPaper() {
        jdbc.sql("UPDATE trading_execution_mode SET active_mode = 'PAPER', updated_by = 'test' WHERE id = 1").update();
    }

    @Test
    void requiresApiKey() throws Exception {
        mvc.perform(get("/api/system/execution-mode")).andExpect(status().isUnauthorized());
    }

    @Test
    void reportsPaperAsActiveAndLiveAsUnavailable() throws Exception {
        mvc.perform(get("/api/system/execution-mode").header(KEY_HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeMode").value("PAPER"))
            .andExpect(jsonPath("$.liveAvailable").value(false))
            .andExpect(jsonPath("$.liveBlockers").isArray());
    }

    @Test
    void liveSwitchWithoutConfirmationIsRejected() throws Exception {
        mvc.perform(put("/api/system/execution-mode").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"LIVE\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void liveSwitchWithConfirmationIsRefusedAndModeUnchanged() throws Exception {
        mvc.perform(put("/api/system/execution-mode").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"LIVE\",\"confirmation\":\"SWITCH TO LIVE\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.activeMode").value("PAPER"))
            .andExpect(jsonPath("$.liveAvailable").value(false));

        mvc.perform(get("/api/system/execution-mode").header(KEY_HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(jsonPath("$.activeMode").value("PAPER"))
            .andExpect(jsonPath("$.liveAvailable").value(false));
    }

    @Test
    void paperRequestIsIdempotent() throws Exception {
        mvc.perform(put("/api/system/execution-mode").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"PAPER\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeMode").value("PAPER"));
    }

    @Test
    void selectedModeRemainsPaperWhenReadAgain() throws Exception {
        mvc.perform(put("/api/system/execution-mode").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"PAPER\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeMode").value("PAPER"));
        mvc.perform(get("/api/system/execution-mode").header(KEY_HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeMode").value("PAPER"));
    }

    @Test
    void invalidModeIsRejected() throws Exception {
        mvc.perform(put("/api/system/execution-mode").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"AUTO\"}"))
            .andExpect(status().isBadRequest());
    }
}
