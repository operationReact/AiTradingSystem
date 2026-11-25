package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FnoIndexHedgeResearchStoreTest {
    private static final String ACCOUNT = "personal_risk_account";
    private static final String NIFTY = "NSE_INDEX|Nifty 50";
    private static final String FUT = "NSE_FO|NIFTY_DEC_178";
    private static final LocalDate EXPIRY = LocalDate.of(2026, 12, 29);
    private static final Instant NOW = Instant.parse("2026-11-23T05:00:00Z");

    @Test
    void exactAccountIndexFutureAndTwoIndependentSourceRightsBoundEachRiskProof() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoIndexHedgeResearchStore(jdbc);
        assertTrue(store.oneCurrentProof(ACCOUNT, NIFTY, FUT, EXPIRY, 75, NOW).isEmpty());
        var calls = mockingDetails(jdbc).getInvocations().stream()
            .filter(i -> "query".equals(i.getMethod().getName())).toList();
        assertEquals(1, calls.size());
        Object[] params = calls.getFirst().getArguments();
        assertEquals(20, params.length);
        String sql = (String) params[0];
        assertEquals(18, sql.chars().filter(ch -> ch == '?').count());
        assertEquals(ACCOUNT, params[2]);
        assertEquals(NIFTY, params[3]);
        assertEquals(FUT, params[4]);
        assertEquals(java.sql.Date.valueOf(EXPIRY), params[5]);
        assertEquals(75, params[6]);
        assertTrue(sql.contains("fno_index_hedge_research_evidence"));
        assertTrue(sql.contains("provider.source_kind = 'EXTERNAL'"));
        assertTrue(sql.contains("nse_fo_index_future_market_depth"));
        assertTrue(sql.contains("portfolio_reconciled_beta_exposure"));
        assertTrue(sql.contains("market_rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("portfolio_rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("market_rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("portfolio_rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("p.captured_at <= ?"));
        assertTrue(sql.contains("p.exposure_first_observed_at <= ?"));
        assertTrue(sql.contains("p.market_first_observed_at <= ?"));
        assertTrue(sql.contains("p.valid_until >= ?"));
        assertTrue(sql.contains("p.retain_until >= ?"));
        assertTrue(sql.contains("LIMIT 2"));
        assertFalse(sql.toUpperCase().contains("INSERT INTO"));
        assertFalse(sql.contains("market_candle"));
    }

    @Test
    void rejectsUnknownAccountOrWrongMarketIdentityBeforeAccessingDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoIndexHedgeResearchStore(jdbc);
        assertThrows(IllegalArgumentException.class, () ->
            store.oneCurrentProof("", NIFTY, FUT, EXPIRY, 75, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.oneCurrentProof(ACCOUNT, "NSE_EQ|stock", FUT, EXPIRY, 75, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.oneCurrentProof(ACCOUNT, NIFTY, "NSE_EQ|stock", EXPIRY, 75, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.oneCurrentProof(ACCOUNT, NIFTY, FUT, EXPIRY, 0, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.oneCurrentProof(ACCOUNT, NIFTY, FUT,
                LocalDate.of(2026, 11, 24), 75, NOW));
        verifyNoInteractions(jdbc);
    }

    @Test
    void portfolioAndBrokerFundsEvidenceStartsEmptyAndIsImmutableUntilExpiry()
            throws Exception {
        try (var resource = getClass().getResourceAsStream(
                "/db/migration/V103__fno_index_hedge_research_evidence.sql")) {
            assertNotNull(resource);
            var schema = new String(resource.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(schema.contains("CREATE TABLE fno_index_hedge_research_evidence"));
            assertTrue(schema.contains("existing_hedges_included BOOLEAN NOT NULL"));
            assertTrue(schema.contains("beta_model_verified BOOLEAN NOT NULL"));
            assertTrue(schema.contains("portfolio_evidence_sha256"));
            assertTrue(schema.contains("beta_model_evidence_sha256"));
            assertTrue(schema.contains("room_risk_evidence_sha256"));
            assertTrue(schema.contains("market_rights_receipt_id UUID NOT NULL"));
            assertTrue(schema.contains("portfolio_rights_receipt_id UUID NOT NULL"));
            assertTrue(schema.contains("max_hedge_lots INTEGER NOT NULL"));
            assertTrue(schema.contains("BEFORE UPDATE OR DELETE"));
            assertTrue(schema.contains("market_first_observed_at AT TIME ZONE"));
            assertFalse(schema.contains("INSERT INTO fno_index_hedge_research_evidence"));
        }
    }

    @Test
    void privatePortfolioBetaAndFundsArePurgedAfterOneDayMaxRetention() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoIndexHedgeResearchStore(jdbc);
        var today = LocalDate.of(2026, 11, 23);
        store.purgeExpired(today);
        verify(jdbc).update(eq(
            "DELETE FROM fno_index_hedge_research_evidence WHERE retain_until < ?"),
            eq(java.sql.Date.valueOf(today)));
    }
}
