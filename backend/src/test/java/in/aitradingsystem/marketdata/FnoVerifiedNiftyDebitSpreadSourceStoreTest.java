package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FnoVerifiedNiftyDebitSpreadSourceStoreTest {
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final String LOW = "NSE_FO|NIFTY_20261127_125_CE";
    private static final String HIGH = "NSE_FO|NIFTY_20261127_130_CE";

    @Test
    void twoOptionLegsRequireIndependentRightsAndOriginalFirstKnownTaxBudgetEvidence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var source = new FnoVerifiedNiftyDebitSpreadSourceStore(jdbc);
        assertTrue(source.oneCurrentSpread("BULL_CALL", LOW, HIGH, NOW).isEmpty());
        var invocations = mockingDetails(jdbc).getInvocations().stream()
            .filter(i -> "query".equals(i.getMethod().getName())).toList();
        assertEquals(1, invocations.size());
        var args = invocations.getFirst().getArguments();
        assertEquals(34, args.length);
        String sql = (String) args[0];
        assertEquals(32, sql.chars().filter(ch -> ch == '?').count());
        assertEquals("BULL_CALL", args[2]);
        assertEquals(LOW, args[3]);
        assertEquals(HIGH, args[4]);
        assertEquals(java.sql.Date.valueOf(LocalDate.of(2026,11,20)), args[5]);
        for (String scope : new String[]{
                "index_rights", "lower_rights", "higher_rights",
                "master_rights", "fee_rights"}) {
            assertTrue(sql.contains(scope + ".receipt_status = 'VERIFIED'"));
            assertTrue(sql.contains(scope + ".permitted_use = 'MODEL_INFERENCE'"));
            assertTrue(sql.contains(scope + ".verified_at <= ?"));
        }
        assertTrue(sql.contains("nse_fo_option_chain_depth_oi"));
        assertTrue(sql.contains("nse_fo_option_spread_fees_risk_capital"));
        assertTrue(sql.contains("e.both_depths_verified"));
        assertTrue(sql.contains("e.oi_lot_normalization_verified"));
        assertTrue(sql.contains("e.costs_taxes_verified"));
        assertTrue(sql.contains("e.capital_budget_verified"));
        assertTrue(sql.contains("e.lower_first_observed_at <= ?"));
        assertTrue(sql.contains("e.higher_first_observed_at <= ?"));
        assertTrue(sql.contains("e.captured_at <= ?"));
        assertTrue(sql.contains("e.valid_until >= ?"));
        assertTrue(sql.contains("LIMIT 2"));
        assertFalse(sql.contains("market_candle"));
    }

    @Test
    void wrongMarketInvalidPairOrFutureLookaheadNeverQueriesSource() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var source = new FnoVerifiedNiftyDebitSpreadSourceStore(jdbc);
        assertThrows(IllegalArgumentException.class, () ->
            source.oneCurrentSpread("INVALID", LOW, HIGH, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            source.oneCurrentSpread("BULL_CALL", LOW, LOW, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            source.oneCurrentSpread("BULL_CALL", "NSE_EQ|OTHER", HIGH, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            source.oneCurrentSpread("BULL_CALL", LOW, HIGH, null));
        verifyNoInteractions(jdbc);
    }

    @Test
    void custodyMustStartEmptyWithSeparateOriginalTimeBidAskDepthOiAndNoDefaults()
            throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V107__fno_verified_nifty_debit_spread_source.sql")) {
            assertNotNull(input);
            String schema = new String(input.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(schema.contains("CREATE TABLE fno_verified_nifty_debit_spread_source"));
            assertTrue(schema.contains("lower_bid_depth_lots BIGINT NOT NULL"));
            assertTrue(schema.contains("higher_ask_depth_lots BIGINT NOT NULL"));
            assertTrue(schema.contains("lower_oi_lots BIGINT NOT NULL"));
            assertTrue(schema.contains("higher_oi_lots BIGINT NOT NULL"));
            assertTrue(schema.contains("lower_first_observed_at TIMESTAMPTZ NOT NULL"));
            assertTrue(schema.contains("higher_first_observed_at TIMESTAMPTZ NOT NULL"));
            assertTrue(schema.contains("cost_budget_source_sha256"));
            assertTrue(schema.contains("both_depths_verified BOOLEAN NOT NULL"));
            assertTrue(schema.contains("lower_quote_rights_receipt_id UUID NOT NULL"));
            assertTrue(schema.contains("higher_quote_rights_receipt_id UUID NOT NULL"));
            assertTrue(schema.contains("BEFORE UPDATE OR DELETE"));
            assertFalse(schema.contains("INSERT INTO fno_verified_nifty_debit_spread_source"));
        }
    }

    @Test
    void verifiedResearchOnlySourceCanEraseExpiredRowsNotOverwriteOrEarlyDelete() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var source = new FnoVerifiedNiftyDebitSpreadSourceStore(jdbc);
        var day = LocalDate.of(2026, 11, 23);
        source.purgeExpired(day);
        verify(jdbc).update(eq(
            "DELETE FROM fno_verified_nifty_debit_spread_source WHERE retain_until < ?"),
            eq(java.sql.Date.valueOf(day)));
        verifyNoMoreInteractions(jdbc);
    }
}
