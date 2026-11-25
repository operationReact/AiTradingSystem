package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FnoVerifiedExpiryBasisPairStoreTest {
    private static final String SPOT = "NSE_EQ|INE002A01018";
    private static final String FUTURE = "NSE_FO|SINGLE_STOCK_NOV26";
    private static final LocalDate EXPIRY = LocalDate.of(2026, 11, 27);
    private static final Instant NOW = Instant.parse("2026-11-23T05:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);

    @Test
    void exactStockFutureAsOfWindowRequiresThreeIndependentRightsAndTwoVerifiedCalendars() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedExpiryBasisPairStore(jdbc);
        assertTrue(store.readExactPair(SPOT, FUTURE, EXPIRY, 250,
            TODAY.minusDays(21), TODAY, NOW).isEmpty());
        var queries = mockingDetails(jdbc).getInvocations().stream()
            .filter(inv -> "query".equals(inv.getMethod().getName())).toList();
        assertEquals(1, queries.size());
        Object[] invocation = queries.getFirst().getArguments();
        assertEquals(32, invocation.length);
        String sql = (String) invocation[0];
        assertEquals(30, sql.chars().filter(ch -> ch == '?').count());
        assertEquals(SPOT, invocation[2]);
        assertEquals(FUTURE, invocation[3]);
        assertEquals(java.sql.Date.valueOf(EXPIRY), invocation[4]);
        assertEquals(250, invocation[5]);
        assertEquals(java.sql.Date.valueOf(TODAY.minusDays(21)), invocation[6]);
        assertEquals(java.sql.Date.valueOf(TODAY), invocation[7]);
        assertEquals(java.sql.Date.valueOf(TODAY), invocation[8]);
        assertTrue(sql.contains("nse_eq_trading_calendar"));
        assertTrue(sql.contains("nse_fo_trading_calendar"));
        assertTrue(sql.contains("nse_eq_near_close_quote"));
        assertTrue(sql.contains("nse_fo_near_close_quote"));
        assertTrue(sql.contains("nse_eq_carry_delivery_costs"));
        assertTrue(sql.contains("spot_rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("futures_rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("carry_rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("spot_rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("futures_rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("carry_rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("s.spot_exchange_at >= cash_cal.session_close_at"));
        assertTrue(sql.contains("s.futures_exchange_at <= cash_cal.session_close_at"));
        assertTrue(sql.contains("s.futures_final_first_available_at >= future_cal.session_close_at"));
        assertTrue(sql.contains("s.spot_final_first_available_at >= cash_cal.session_close_at"));
        assertTrue(sql.contains("s.captured_at <= ?"));
        assertTrue(sql.contains("future_cal.verified_at <= ?"));
        assertTrue(sql.contains("cash_cal.verified_at <= ?"));
        assertTrue(sql.contains("ORDER BY s.session_date, s.provider_code"));
        assertTrue(sql.contains("LIMIT 40"));
        assertFalse(sql.toUpperCase().contains("INSERT INTO"));
        assertFalse(sql.contains("market_candle"));
        assertFalse(sql.contains("fno_closing_snapshot"));
    }

    @Test
    void invalidCashStockExpiryWindowOrLookaheadRejectedWithoutQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedExpiryBasisPairStore(jdbc);
        assertThrows(IllegalArgumentException.class, () ->
            store.readExactPair("NSE_INDEX|Nifty 50", FUTURE, EXPIRY, 250,
                TODAY.minusDays(21), TODAY, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.readExactPair(SPOT, "NSE_EQ|STOCK", EXPIRY, 250,
                TODAY.minusDays(21), TODAY, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.readExactPair(SPOT, FUTURE, EXPIRY, 0,
                TODAY.minusDays(21), TODAY, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.readExactPair(SPOT, FUTURE, EXPIRY, 250,
                TODAY.minusDays(22), TODAY, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.readExactPair(SPOT, FUTURE, EXPIRY, 250,
                TODAY.plusDays(1), TODAY.plusDays(2), NOW));
        verifyNoInteractions(jdbc);
    }

    @Test
    void migrationLeavesAllFinalQuoteCarryAndCostSourceReceiptsUnseeded()
            throws Exception {
        try (var resource = getClass().getResourceAsStream(
                "/db/migration/V105__fno_verified_expiry_basis_pair_evidence.sql")) {
            assertNotNull(resource);
            String ddl = new String(resource.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(ddl.contains("CREATE TABLE fno_verified_expiry_basis_pair_evidence"));
            assertTrue(ddl.contains("spot_final_first_available_at TIMESTAMPTZ NOT NULL"));
            assertTrue(ddl.contains("futures_final_first_available_at TIMESTAMPTZ NOT NULL"));
            assertTrue(ddl.contains("carry_cost_source_digest CHAR(64)"));
            assertTrue(ddl.contains("physical_delivery_reviewed BOOLEAN NOT NULL"));
            assertTrue(ddl.contains("dividend_events_verified BOOLEAN NOT NULL"));
            assertTrue(ddl.contains("spot_calendar_evidence_id UUID NOT NULL"));
            assertTrue(ddl.contains("futures_calendar_evidence_id UUID NOT NULL"));
            assertTrue(ddl.contains("spot_rights_receipt_id UUID NOT NULL"));
            assertTrue(ddl.contains("futures_rights_receipt_id UUID NOT NULL"));
            assertTrue(ddl.contains("carry_cost_rights_receipt_id UUID NOT NULL"));
            assertTrue(ddl.contains("BEFORE UPDATE OR DELETE"));
            assertFalse(ddl.contains("INSERT INTO fno_verified_expiry_basis_pair_evidence"));
        }
    }

    @Test
    void retentionPurgeCannotBeUsedForUnexpiredRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedExpiryBasisPairStore(jdbc);
        store.purgeExpired(TODAY);
        verify(jdbc).update(eq(
            "DELETE FROM fno_verified_expiry_basis_pair_evidence WHERE retain_until < ?"),
            eq(java.sql.Date.valueOf(TODAY)));
    }
}
