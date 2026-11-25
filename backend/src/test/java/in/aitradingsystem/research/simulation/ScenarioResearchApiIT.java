package in.aitradingsystem.research.simulation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

class ScenarioResearchApiIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";
    private static final UUID SCAN_ID = UUID.fromString("91000000-0000-0000-0000-000000000001");
    private static final UUID CATALOGUE_VERSION = UUID.fromString("92000000-0000-0000-0000-000000000002");
    private static final OffsetDateTime SIGNAL_TIME = OffsetDateTime.of(2026, 6, 30, 9, 55, 0, 0, ZoneOffset.UTC);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean ScenarioSimulationService simulations;

    private long candidateId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM research_evaluation");
        jdbc.update("DELETE FROM research_scenario_run");
        jdbc.update("DELETE FROM research_scan_candidate");
        jdbc.update("DELETE FROM research_scan_run");
        jdbc.update("DELETE FROM market_candle WHERE instrument_id = 919191");

        jdbc.update("""
                INSERT INTO research_scan_run
                    (id, interval, status, requested_limit, scanned_count, candidate_count, strategy_set, completed_at)
                VALUES (?, 'FIVE_MINUTE', 'SUCCEEDED', 1, 1, 1, 'OPENING_RANGE_BREAKOUT_V1', now())
                """, SCAN_ID);
        jdbc.update("""
                INSERT INTO market_candle
                    (instrument_id, exchange, trading_symbol, symbol_token, catalogue_version, interval,
                     candle_time, open_price, high_price, low_price, close_price, volume)
                VALUES (919191, 'NSE', 'RELIANCE', '919191', ?, 'FIVE_MINUTE', ?,
                        2495, 2510, 2490, 2500, 100000)
                """, CATALOGUE_VERSION, SIGNAL_TIME);

        candidateId = jdbc.queryForObject("""
                INSERT INTO research_scan_candidate
                    (scan_run_id, instrument_id, trading_symbol, exchange, interval, strategy, score,
                     entry_price, stop_price, target_price, signal_candle_time, reasons,
                     candidate_fingerprint, catalogue_version, data_cutoff_at)
                VALUES (?, 919191, 'RELIANCE', 'NSE', 'FIVE_MINUTE', 'OPENING_RANGE_BREAKOUT_V1', 0.90,
                        2500, 2450, 2600, ?, 'gate-fixture', ?, ?, ?)
                RETURNING id
                """, Long.class, SCAN_ID, SIGNAL_TIME, "f".repeat(64), CATALOGUE_VERSION, SIGNAL_TIME);

        when(simulations.classifyRegime(any())).thenReturn(new RegimeSnapshot(
                .031, .012, .024, MarketRegime.HIGH_VOL,
                1.18, LiquidityRegime.NORMAL, RegimeClassifier.METHODOLOGY));
        when(simulations.simulate(any())).thenReturn(new ScenarioSimulationResult(
                HistoricalScenarioSimulator.METHODOLOGY, 424242L, 1_000, 480, 350,
                BlockSamplingFallbackTier.EXACT_VOLATILITY_AND_VOLUME.name(), 20, 5,
                550, 300, 150, .55, .30, .15, .50, -1.02, .40, 2.05));
    }

    @Test
    void candidateDetailReturnsStoredProvenanceWithoutRunningAnEvaluation() throws Exception {
        mvc.perform(get("/api/research/candidates/{id}", candidateId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/research/candidates/{id}", candidateId).header(KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(candidateId))
                .andExpect(jsonPath("$.scanRunId").value(SCAN_ID.toString()))
                .andExpect(jsonPath("$.instrumentId").value(919191))
                .andExpect(jsonPath("$.strategy").value("OPENING_RANGE_BREAKOUT_V1"))
                .andExpect(jsonPath("$.catalogueVersion").value(CATALOGUE_VERSION.toString()))
                .andExpect(jsonPath("$.candidateFingerprint").value("f".repeat(64)))
                .andExpect(jsonPath("$.entryPrice").value(2500));
        mvc.perform(get("/api/research/candidates/{id}", Long.MAX_VALUE).header(KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/research/candidates/0").header(KEY_HEADER, API_KEY))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(simulations);
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT count(*) FROM research_evaluation", Integer.class));
    }

    @Test
    void rankedCandidateApiExposesThePersistedIdentityUsedByTheDetailRoute() throws Exception {
        mvc.perform(get("/api/research/scans/{id}/candidates", SCAN_ID)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/research/scans/{id}/candidates", SCAN_ID).header(KEY_HEADER, API_KEY))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].candidateId").value(candidateId))
            .andExpect(jsonPath("$[0].referencePrice").value(2500));
    }

    @Test
    void candidateEvaluationPersistsReproducibleRegimeAwareEvidenceAndExposesItThroughApis() throws Exception {
        String body = """
                {"historyFrom":"2026-01-01T03:45:00Z","historyTo":"2026-06-30T09:55:00Z",
                 "simulations":1000,"horizonBars":20,"blockSize":5,"seed":424242,
                 "slippageBps":5,"roundTripCostBps":10}
                """;

        String firstResponse = mvc.perform(post("/api/research/candidates/{candidateId}/evaluate", candidateId)
                        .header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateId").value(candidateId))
                .andExpect(jsonPath("$.decisionLabel").value("FAVORABLE"))
                .andExpect(jsonPath("$.scenarioReused").value(false))
                .andExpect(jsonPath("$.reasonCodes[0]").value("POSITIVE_EXPECTANCY"))
                .andReturn().getResponse().getContentAsString();

        JsonNode first = objectMapper.readTree(firstResponse);
        String scenarioRunId = first.path("scenarioRunId").asText();

        mvc.perform(post("/api/research/candidates/{candidateId}/evaluate", candidateId)
                        .header(KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioRunId").value(scenarioRunId))
                .andExpect(jsonPath("$.scenarioReused").value(true));

        mvc.perform(get("/api/research/candidates/{candidateId}/evaluations", candidateId)
                        .header(KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].candidateId").value(candidateId))
                .andExpect(jsonPath("$[0].scenarioRunId").value(scenarioRunId))
                .andExpect(jsonPath("$[0].decisionLabel").value("FAVORABLE"))
                .andExpect(jsonPath("$[0].reasonCodes[0]").value("POSITIVE_EXPECTANCY"));

        mvc.perform(get("/api/research/simulations/{id}", scenarioRunId)
                        .header(KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateId").value(candidateId))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.regime").value("HIGH_VOL"))
                .andExpect(jsonPath("$.fallbackTier").value("EXACT_VOLATILITY_AND_VOLUME"))
                .andExpect(jsonPath("$.seed").value(424242))
                .andExpect(jsonPath("$.eligibleBlockCount").value(350))
                .andExpect(jsonPath("$.requestFingerprint").isNotEmpty())
                .andExpect(jsonPath("$.methodology").value(HistoricalScenarioSimulator.METHODOLOGY));

        verify(simulations, times(1)).classifyRegime(any());
        verify(simulations, times(1)).simulate(any());
    }

    @Test
    void researchEvaluationEndpointsRemainProtectedByOperatorApiKey() throws Exception {
        mvc.perform(get("/api/research/candidates/{candidateId}/evaluations", candidateId))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/research/simulations/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
