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

class TradingControlApiIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void resetControls() {
        jdbc.sql("""
            UPDATE trading_execution_mode
               SET active_mode = 'PAPER', order_execution_mode = 'MANUAL', updated_by = 'test'
             WHERE id = 1
            """).update();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/system/trading-control"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void defaultsToPaperAndManual() throws Exception {
        mvc.perform(get("/api/system/trading-control").header(KEY_HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tradingMode").value("PAPER"))
            .andExpect(jsonPath("$.executionMode").value("MANUAL"))
            .andExpect(jsonPath("$.liveAvailable").value(false));
    }

    @Test
    void userCanEnableAutomatedPaperWithoutEnablingLive() throws Exception {
        mvc.perform(put("/api/system/trading-control").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tradingMode\":\"PAPER\",\"executionMode\":\"AUTOMATED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tradingMode").value("PAPER"))
            .andExpect(jsonPath("$.executionMode").value("AUTOMATED"))
            .andExpect(jsonPath("$.liveAvailable").value(false));

        mvc.perform(get("/api/system/trading-control").header(KEY_HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tradingMode").value("PAPER"))
            .andExpect(jsonPath("$.executionMode").value("AUTOMATED"));
    }

    @Test
    void executionModeCanChangeIndependentlyOfTradingMode() throws Exception {
        mvc.perform(put("/api/system/trading-control").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"executionMode\":\"AUTOMATED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tradingMode").value("PAPER"))
            .andExpect(jsonPath("$.executionMode").value("AUTOMATED"));

        mvc.perform(put("/api/system/trading-control").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"executionMode\":\"MANUAL\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tradingMode").value("PAPER"))
            .andExpect(jsonPath("$.executionMode").value("MANUAL"));
    }

    @Test
    void liveRemainsFailClosedEvenWhenAutomatedWasSelected() throws Exception {
        mvc.perform(put("/api/system/trading-control").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"executionMode\":\"AUTOMATED\"}"))
            .andExpect(status().isOk());

        mvc.perform(put("/api/system/trading-control").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tradingMode\":\"LIVE\",\"confirmation\":\"SWITCH TO LIVE\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.tradingMode").value("PAPER"))
            .andExpect(jsonPath("$.liveAvailable").value(false));

        mvc.perform(get("/api/system/trading-control").header(KEY_HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tradingMode").value("PAPER"))
            .andExpect(jsonPath("$.executionMode").value("AUTOMATED"));
    }
}
