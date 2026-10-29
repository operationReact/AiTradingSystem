package in.aitradingsystem.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class PositionSizingApiIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";
    private static final String MASTER = """
            [{"token":"99001","symbol":"SIZE-EQ","name":"POSITION SIZE TEST","expiry":"","strike":"-1.000000","lotsize":"1","instrumenttype":"EQ","exch_seg":"nse_cm","tick_size":"5.000000"}]
            """;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired InstrumentMasterService instruments;
    @Autowired RiskPolicyConfigService policies;
    @Autowired RiskPolicyConfigRepository policyRepository;

    private long instrumentId;

    @BeforeEach
    void prepareActiveInstrumentAndPolicy() {
        jdbc.update("DELETE FROM instrument_master");
        jdbc.update("UPDATE instrument_catalogue_state SET active_version = NULL, activated_at = NULL WHERE singleton_id = 1");
        jdbc.update("DELETE FROM instrument_import_run");
        var imported = instruments.importSource(MASTER.getBytes(StandardCharsets.UTF_8));
        assertThat(imported.status()).isEqualTo("SUCCEEDED");
        instrumentId = jdbc.queryForObject(
                "SELECT instrument_id FROM instrument_master WHERE trading_symbol = 'SIZE-EQ' AND active", Long.class);
        policies.createAndActivate(apiPolicy(), "POSITION_SIZE_IT");
    }

    @Test
    void endpointIsProtectedAndUsesServerOwnedCashInsteadOfClientTamperField() throws Exception {
        String request = requestJson(true);
        mvc.perform(post("/api/risk/position-size")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());

        BigDecimal cash = jdbc.queryForObject("SELECT cash_balance FROM paper_account WHERE id = 1", BigDecimal.class);
        int expectedCashCap = cash.divideToIntegralValue(new BigDecimal("100.100")).intValue();
        int policyVersion = policyRepository.requireActive().version();

        mvc.perform(post("/api/risk/position-size")
                        .header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityCaps.CASH_AVAILABILITY").value(expectedCashCap))
                .andExpect(jsonPath("$.policyVersion").value(policyVersion));
    }

    @Test
    void rejectsTradeSetupThatDoesNotMatchActiveInstrumentIdentity() throws Exception {
        String request = """
                {"instrumentId":%d,"exchange":"NSE","symbol":"OTHER-EQ","entryPrice":100,
                 "stopLoss":95,"targetPrice":110,"strategy":"TEST","tradingStyle":"INTRADAY"}
                """.formatted(instrumentId);
        mvc.perform(post("/api/risk/position-size")
                        .header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
    }

    private String requestJson(boolean includeTamperFields) {
        String tamper = includeTamperFields
                ? ",\"availableCash\":999999999,\"policyVersion\":999,\"currentGrossExposure\":0"
                : "";
        return "{\"instrumentId\":" + instrumentId
                + ",\"exchange\":\"NSE\",\"symbol\":\"SIZE-EQ\",\"entryPrice\":100,\"stopLoss\":95"
                + ",\"targetPrice\":110,\"strategy\":\"TEST\",\"tradingStyle\":\"INTRADAY\"" + tamper + "}";
    }

    private static CreateRiskPolicyRequest apiPolicy() {
        return new CreateRiskPolicyRequest(
                "Position sizing API policy",
                new BigDecimal("5.00"), new BigDecimal("10.00"), 20,
                BigDecimal.ONE, new BigDecimal("999999999999999.99"),
                new BigDecimal("100.00"), new BigDecimal("100.00"), 1000,
                30, 3600, false, true, false,
                new BigDecimal("0.00100000"), BigDecimal.ZERO, "position-size-api-it");
    }
}
