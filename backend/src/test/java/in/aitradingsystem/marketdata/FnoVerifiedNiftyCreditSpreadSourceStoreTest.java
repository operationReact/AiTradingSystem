package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FnoVerifiedNiftyCreditSpreadSourceStoreTest {
    private static final String ACCOUNT = "personal_margin_acct";
    private static final String LOWER = "NSE_FO|NIFTY_20261127_120_PE";
    private static final String HIGHER = "NSE_FO|NIFTY_20261127_125_PE";
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");

    @Test
    void exactAccountAndSixOriginalRightsAreRequiredForBrokerMarginSource() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedNiftyCreditSpreadSourceStore(jdbc);
        assertTrue(store.oneCurrentSpread(ACCOUNT, "BULL_PUT", LOWER, HIGHER, NOW).isEmpty());
        var calls = mockingDetails(jdbc).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("query")).toList();
        assertEquals(1, calls.size());
        Object[] args = calls.getFirst().getArguments();
        String sql = (String) args[0];
        assertEquals(37, sql.chars().filter(ch -> ch == '?').count());
        assertEquals(39, args.length);
        assertEquals(ACCOUNT, args[2]);
        assertEquals("BULL_PUT", args[3]);
        assertEquals(LOWER, args[4]);
        assertEquals(HIGHER, args[5]);
        assertEquals(java.sql.Date.valueOf(LocalDate.of(2026,11,20)), args[6]);
        assertTrue(sql.contains("e.account_scope = ?"));
        for (String grant : new String[]{
            "index_rights", "lower_rights", "higher_rights",
            "master_rights", "fee_rights", "margin_rights"}) {
            assertTrue(sql.contains(grant + ".receipt_status = 'VERIFIED'"), grant);
            assertTrue(sql.contains(grant + ".permitted_use = 'MODEL_INFERENCE'"), grant);
            assertTrue(sql.contains(grant + ".verified_at <= ?"), grant);
        }
        assertTrue(sql.contains("nse_fo_broker_credit_spread_margin"));
        assertTrue(sql.contains("e.broker_margin_reconciled"));
        assertTrue(sql.contains("e.existing_positions_reconciled"));
        assertTrue(sql.contains("e.assignment_delivery_reviewed"));
        assertTrue(sql.contains("e.stressed_loss_reviewed"));
        assertTrue(sql.contains("e.capital_budget_verified"));
        assertTrue(sql.contains("e.retain_until >= ?"));
        assertTrue(sql.contains("e.captured_at <= ?"));
        assertTrue(sql.contains("LIMIT 2"));
        assertFalse(sql.contains("market_candle"));
    }

    @Test
    void badAccountWrongOptionTypeOrSameLegNeverReadsBrokerProof() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedNiftyCreditSpreadSourceStore(jdbc);
        assertThrows(IllegalArgumentException.class, () ->
            store.oneCurrentSpread("", "BULL_PUT", LOWER, HIGHER, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.oneCurrentSpread(ACCOUNT, "BULL_CALL", LOWER, HIGHER, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.oneCurrentSpread(ACCOUNT, "BULL_PUT", LOWER, LOWER, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.oneCurrentSpread(ACCOUNT, "BULL_PUT", "NSE_EQ|RANDOM", HIGHER, NOW));
        verifyNoInteractions(jdbc);
    }

    @Test
    void accountSourceTableStartsEmptyAndCarriesNoAssumedMarginOrCostFigures()
            throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V108__fno_verified_nifty_credit_spread_margin_source.sql")) {
            assertNotNull(input);
            String ddl = new String(input.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(1, ddl.split("CREATE TABLE", -1).length - 1);
            assertEquals(1, ddl.split("CREATE FUNCTION", -1).length - 1);
            assertEquals(1, ddl.split("CREATE TRIGGER", -1).length - 1);
            assertTrue(ddl.contains("account_scope VARCHAR(80) NOT NULL"));
            assertTrue(ddl.contains("account_scope ~ '^[A-Za-z0-9_-]{8,80}$'"));
            assertTrue(ddl.contains("available_cash_inr NUMERIC(24,4) NOT NULL"));
            assertTrue(ddl.contains("initial_margin_per_spread_lot_inr NUMERIC(24,4) NOT NULL"));
            assertTrue(ddl.contains("stressed_variation_reserve_inr NUMERIC(24,4) NOT NULL"));
            assertTrue(ddl.contains("settlement_assignment_buffer_inr NUMERIC(24,4) NOT NULL"));
            assertTrue(ddl.contains("stressed_max_loss_per_spread_lot_inr NUMERIC(24,4) NOT NULL"));
            assertTrue(ddl.contains("broker_margin_reconciled BOOLEAN NOT NULL"));
            assertTrue(ddl.contains("existing_positions_reconciled BOOLEAN NOT NULL"));
            assertTrue(ddl.contains("assignment_delivery_reviewed BOOLEAN NOT NULL"));
            assertTrue(ddl.contains("stressed_loss_reviewed BOOLEAN NOT NULL"));
            assertTrue(ddl.contains("broker_margin_rights_receipt_id UUID NOT NULL"));
            assertTrue(ddl.contains("ck_fno_credit_spread_margin"));
            assertTrue(ddl.contains("BEFORE UPDATE OR DELETE"));
            assertFalse(ddl.contains("INSERT INTO fno_verified_nifty_credit_spread_source"));
        }
    }

    @Test
    void sensitiveMarginProofsCanOnlyBePurgedAfterRetentionExpiry() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedNiftyCreditSpreadSourceStore(jdbc);
        LocalDate date = LocalDate.of(2026,11,23);
        store.purgeExpired(date);
        verify(jdbc).update(eq(
            "DELETE FROM fno_verified_nifty_credit_spread_source WHERE retain_until < ?"),
            eq(java.sql.Date.valueOf(date)));
        verifyNoMoreInteractions(jdbc);
    }
}
