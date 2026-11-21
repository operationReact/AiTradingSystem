package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FnoCashFuturesFeasibilityStoreTest {
    private static final String ACCOUNT = "personal_proof_01";
    private static final String SPOT = "NSE_EQ|INE002A01018";
    private static final String FUTURE = "NSE_FO|FUTDEC101";
    private static final LocalDate EXPIRY = LocalDate.of(2026, 12, 29);
    private static final Instant NOW = Instant.parse("2026-11-23T05:00:00Z");

    @Test
    void queryIsBoundedToOneAccountTwoVerifiedMarketResearchRightsAndAsOfDepth() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var read = new FnoCashFuturesFeasibilityStore(jdbc);
        assertTrue(read.currentSingleProof(ACCOUNT, SPOT, FUTURE, EXPIRY, 250, NOW).isEmpty());
        var queryCalls = mockingDetails(jdbc).getInvocations().stream()
            .filter(i -> "query".equals(i.getMethod().getName())).toList();
        assertEquals(1, queryCalls.size());
        Object[] args = queryCalls.getFirst().getArguments();
        assertEquals(19, args.length);
        String sql = (String) args[0];
        assertEquals(17, sql.chars().filter(c -> c == '?').count());
        assertEquals(ACCOUNT, args[2]);
        assertEquals(SPOT, args[3]);
        assertEquals(FUTURE, args[4]);
        assertEquals(java.sql.Date.valueOf(EXPIRY), args[5]);
        assertEquals(250, args[6]);
        assertTrue(sql.contains("JOIN golden_data_provider"));
        assertTrue(sql.contains("p.source_kind = 'EXTERNAL'"));
        assertTrue(sql.contains("nse_eq_quote_depth"));
        assertTrue(sql.contains("nse_fo_quote_depth"));
        assertTrue(sql.contains("spot_rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("future_rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("spot_rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("future_rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("e.account_scope = ?"));
        assertTrue(sql.contains("e.depth_first_available_at <= ?"));
        assertTrue(sql.contains("e.captured_at <= ?"));
        assertTrue(sql.contains("e.valid_until >= ?"));
        assertTrue(sql.contains("e.retain_until >= ?"));
        assertTrue(sql.contains("ORDER BY e.depth_first_available_at DESC"));
        assertTrue(sql.contains("LIMIT 2"));
        assertFalse(sql.toUpperCase().contains("INSERT INTO"));
        assertFalse(sql.toUpperCase().contains("UPDATE "));
        assertFalse(sql.contains("market_candle"));
    }

    @Test
    void invalidAccountUnderlyingExpiryAndDatesNeverTouchSql() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var read = new FnoCashFuturesFeasibilityStore(jdbc);
        assertThrows(IllegalArgumentException.class, () ->
            read.currentSingleProof("", SPOT, FUTURE, EXPIRY, 250, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            read.currentSingleProof(ACCOUNT, "NSE_INDEX|Nifty 50", FUTURE, EXPIRY, 250, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            read.currentSingleProof(ACCOUNT, SPOT, "NSE_EQ|stock", EXPIRY, 250, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            read.currentSingleProof(ACCOUNT, SPOT, FUTURE,
                LocalDate.of(2026, 11, 24), 250, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            read.currentSingleProof(ACCOUNT, SPOT, FUTURE, EXPIRY, 0, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            read.currentSingleProof(ACCOUNT, SPOT, FUTURE, EXPIRY, 250, null));
        verifyNoInteractions(jdbc);
    }

    @Test
    void privateAccountFinancialEvidenceCanOnlyBePurgedAfterRetentionExpiry() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoCashFuturesFeasibilityStore(jdbc);
        LocalDate today = LocalDate.of(2026, 11, 23);
        store.purgeExpired(today);
        verify(jdbc).update(eq(
            "DELETE FROM fno_cash_futures_feasibility_evidence WHERE retain_until < ?"),
            eq(java.sql.Date.valueOf(today)));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void migrationIsEmptyImmutableAccountScopedAndDoesNotDefaultBrokerFinancialInputs()
            throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V101__fno_cash_futures_feasibility_evidence.sql")) {
            assertNotNull(stream);
            String ddl = new String(stream.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(ddl.contains("CREATE TABLE fno_cash_futures_feasibility_evidence"));
            assertTrue(ddl.contains("golden_source_rights_receipt"));
            assertTrue(ddl.contains("account_scope VARCHAR(80)"));
            assertTrue(ddl.contains("spot_ask_at_depth"));
            assertTrue(ddl.contains("futures_bid_at_depth"));
            assertTrue(ddl.contains("stressed_variation_margin_inr"));
            assertTrue(ddl.contains("margin_evidence_sha256"));
            assertTrue(ddl.contains("fees_evidence_sha256"));
            assertTrue(ddl.contains("delivery_identity_verified"));
            assertTrue(ddl.contains("retain_until DATE NOT NULL"));
            assertTrue(ddl.contains("depth_first_available_at AT TIME ZONE"));
            assertTrue(ddl.contains("IF TG_OP = 'DELETE'"));
            assertTrue(ddl.contains("BEFORE UPDATE OR DELETE"));
            assertFalse(ddl.contains("INSERT INTO fno_cash_futures_feasibility_evidence"));
            assertFalse(ddl.contains("mock-broker"));
        }
    }
}
