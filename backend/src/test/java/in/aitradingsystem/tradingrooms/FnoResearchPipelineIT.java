package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.marketdata.FnoClosingSnapshotStore;
import in.aitradingsystem.marketdata.FnoClosingSnapshotStore.Outcome;
import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.tradingrooms.FnoFuturesContractRegistry.NearFar;
import in.aitradingsystem.provider.upstox.UpstoxFuturesInstrumentMaster.FuturesContract;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PostgreSQL-backed pipeline test: closing-snapshot write, pair history read through the
 * research service, retention purge, and the secured admin status endpoint. Runs against
 * the runner-local PostgreSQL in CI (ATS_TEST_POSTGRES_JDBC_URL).
 */
@TestPropertySource(properties = {
    "minttick.fno-research.persist-enabled=true",
    "minttick.fno-research.research-rights-verified=false",
    "minttick.fno-research.contract-discovery-verified=false"
})
class FnoResearchPipelineIT extends AbstractPostgresIT {
    static final String NEAR_KEY = "NSE_FO|IT-NEAR-01";
    static final String FAR_KEY = "NSE_FO|IT-FAR-02";
    static final String NIFTY = "NSE_INDEX|Nifty 50";

    @Autowired FnoClosingSnapshotStore store;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** Shared PostgreSQL keeps rows between runs: clear this test's keys before each case. */
    @org.junit.jupiter.api.BeforeEach
    void clearOwnRows() {
        jdbc.update("DELETE FROM fno_closing_snapshot WHERE instrument_key LIKE 'NSE_FO|IT-%'");
    }
    @Autowired FnoFuturesResearchService service;
    @Autowired MockMvc mvc;

    private static FnoClosingSnapshotStore.Snapshot snap(String key, LocalDate date, long oi, long volume) {
        Instant close = date.atTime(15, 30).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        return new FnoClosingSnapshotStore.Snapshot("UPSTOX_ANALYTICS", key, NIFTY, date,
            close.minusSeconds(120), close.plusSeconds(60),
            new BigDecimal("24990"), new BigDecimal("24992"), oi, volume, date.plusDays(30), true);
    }

    @Test
    void storedPairHistoryFlowsIntoRolloverEvaluationInputs() {
        LocalDate d1 = LocalDate.of(2026, 11, 16), d2 = LocalDate.of(2026, 11, 17), d3 = LocalDate.of(2026, 11, 18);
        // Use dates inside the 14-day lookback relative to the test "today" below.
        LocalDate today = LocalDate.of(2026, 11, 20);
        for (LocalDate d : List.of(d1, d2, d3)) {
            assertEquals(Outcome.STORED, store.save(snap(NEAR_KEY, d, 1000, 500), true));
            assertEquals(Outcome.STORED, store.save(snap(FAR_KEY, d, 200, 300), true));
        }
        // Duplicate natural key is ignored, not overwritten.
        assertEquals(Outcome.DUPLICATE_IGNORED, store.save(snap(NEAR_KEY, d1, 9999, 9999), true));

        var near = new FuturesContract(NEAR_KEY, NIFTY, "NIFTY", "NIFTY FUT NEAR", LocalDate.of(2026, 11, 26), 75);
        var far = new FuturesContract(FAR_KEY, NIFTY, "NIFTY", "NIFTY FUT FAR", LocalDate.of(2026, 12, 29), 75);
        var sessions = service.completedSessions(new NearFar(near, far), today);
        assertEquals(3, sessions.size());
        assertEquals(d1, sessions.get(0).sessionDate());
        assertEquals(1000L, sessions.get(0).near().openInterestUnits());
        assertEquals(500L, sessions.get(0).near().sessionVolumeUnits());
        assertEquals("UPSTOX_ANALYTICS", sessions.get(2).far().source());
        // Duplicate was not overwritten.
        assertEquals(1000L, sessions.get(0).near().openInterestUnits());
    }

    @Test
    void retentionPurgeRemovesOnlyExpiredRows() {
        LocalDate session = LocalDate.of(2026, 1, 5);
        store.save(new FnoClosingSnapshotStore.Snapshot("ANGEL_ONE_SMARTAPI", "NSE_FO|IT-OLD-03", NIFTY, session,
            session.atTime(15, 20).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant(),
            session.atTime(15, 31).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant(),
            new BigDecimal("1"), new BigDecimal("2"), 1L, 1L, LocalDate.of(2026, 2, 1), true), true);
        int purged = store.purgeExpired(LocalDate.of(2026, 11, 20));
        assertTrue(purged >= 1, "expired row should be purged");
        assertTrue(store.pairRows("NSE_FO|IT-OLD-03", "NSE_FO|IT-OLD-04", session, session).isEmpty());
    }

    @Test
    void adminStatusEndpointRequiresKeyAndReportsBlockedRightsByDefault() throws Exception {
        mvc.perform(get("/api/admin/research/fno-futures/status").header("X-API-Key", API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].strategyId").value("strategy.STR-170"))
            .andExpect(jsonPath("$[1].strategyId").value("strategy.STR-171"))
            .andExpect(jsonPath("$[2].strategyId").value("strategy.STR-172"))
            .andExpect(jsonPath("$[1].status").value("BLOCKED_RIGHTS"))
            .andExpect(jsonPath("$[2].status").value("BLOCKED_RIGHTS"));
        mvc.perform(get("/api/admin/research/fno-futures/status"))
            .andExpect(status().isUnauthorized());
    }
}
