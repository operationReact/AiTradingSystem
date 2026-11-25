package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FnoVerifiedNiftyLongOptionSourceStoreTest {
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final String CE = "NSE_FO|NIFTY_NOV2026_125_CE";

    @Test
    void dailyIndexSourceRequiresRealExchangeCalendarOriginalTimestampAndSeparateRights() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var source = new FnoVerifiedNiftyLongOptionSourceStore(jdbc);
        source.verifiedRecentCloses(NOW);
        var q = mockingDetails(jdbc).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("query")).toList();
        assertEquals(1, q.size());
        var args = q.getFirst().getArguments();
        String sql = (String) args[0];
        assertEquals(13, sql.chars().filter(x -> x == '?').count());
        assertEquals(15, args.length);
        assertEquals("NSE_INDEX|Nifty 50", args[2]);
        assertTrue(sql.contains("c.first_available_at >= cal.session_close_at"));
        assertTrue(sql.contains("c.first_available_at <= ?"));
        assertTrue(sql.contains("nse_index_trading_calendar"));
        assertTrue(sql.contains("nse_nifty_completed_daily_close"));
        assertTrue(sql.contains("rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("cal.verified_at <= ?"));
        assertTrue(sql.contains("c.captured_at <= ?"));
        assertTrue(sql.contains("LIMIT 100"));
        assertFalse(sql.contains("market_candle"));
    }

    @Test
    void optionContractAndFourSeparateResearchRightsAreRequiredAsOf() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var source = new FnoVerifiedNiftyLongOptionSourceStore(jdbc);
        assertTrue(source.oneCurrentOption(CE, "CE", NOW).isEmpty());
        var queries = mockingDetails(jdbc).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("query")).toList();
        assertEquals(1, queries.size());
        var args = queries.getFirst().getArguments();
        String sql = (String) args[0];
        assertEquals(25, sql.chars().filter(ch -> ch == '?').count());
        assertEquals(27, args.length);
        assertEquals(CE, args[2]);
        assertEquals("CE", args[3]);
        assertTrue(sql.contains("nse_nifty_underlying_tick"));
        assertTrue(sql.contains("nse_fo_option_chain_depth_oi"));
        assertTrue(sql.contains("nse_fo_option_instrument_master"));
        assertTrue(sql.contains("nse_fo_option_fees_research_capital"));
        for (String rights : new String[]{
                "tick_rights", "option_rights", "master_rights", "risk_rights"}) {
            assertTrue(sql.contains(rights + ".permitted_use = 'MODEL_INFERENCE'"));
            assertTrue(sql.contains(rights + ".receipt_status = 'VERIFIED'"));
            assertTrue(sql.contains(rights + ".valid_from <= ?"));
            assertTrue(sql.contains(rights + ".verified_at <= ?"));
        }
        assertTrue(sql.contains("q.contract_master_verified"));
        assertTrue(sql.contains("q.lot_units_reconciled"));
        assertTrue(sql.contains("q.fee_tax_schedule_verified"));
        assertTrue(sql.contains("q.research_budget_verified"));
        assertTrue(sql.contains("q.option_first_observed_at <= ?"));
        assertTrue(sql.contains("q.index_first_observed_at <= ?"));
        assertTrue(sql.contains("q.valid_until >= ?"));
        assertTrue(sql.contains("LIMIT 2"));
        assertFalse(sql.contains("option_chain_synthetic"));
    }

    @Test
    void malformedOrWrongSideCannotSelectArbitraryFutureInstrument() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var source = new FnoVerifiedNiftyLongOptionSourceStore(jdbc);
        assertThrows(IllegalArgumentException.class, () ->
            source.oneCurrentOption("NSE_EQ|STOCK", "CE", NOW));
        assertThrows(IllegalArgumentException.class, () ->
            source.oneCurrentOption(CE, "C", NOW));
        assertThrows(IllegalArgumentException.class, () ->
            source.oneCurrentOption(CE, "PE", null));
        verifyNoInteractions(jdbc);
    }

    @Test
    void sourceCustodyStartsEmptyAndIncludesOriginalObservedRightsLotOiCosts()
            throws Exception {
        try (var file = getClass().getResourceAsStream(
                "/db/migration/V106__fno_nifty_long_option_source_evidence.sql")) {
            assertNotNull(file);
            String sql = new String(file.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(sql.contains("CREATE TABLE fno_verified_nifty_daily_close"));
            assertTrue(sql.contains("CREATE TABLE fno_verified_nifty_long_option_quote"));
            assertTrue(sql.contains("first_available_at TIMESTAMPTZ NOT NULL"));
            assertTrue(sql.contains("option_first_observed_at TIMESTAMPTZ NOT NULL"));
            assertTrue(sql.contains("ask_depth_lots BIGINT NOT NULL"));
            assertTrue(sql.contains("open_interest_lots BIGINT NOT NULL"));
            assertTrue(sql.contains("contract_master_sha256"));
            assertTrue(sql.contains("fee_tax_source_sha256"));
            assertTrue(sql.contains("risk_budget_source_sha256"));
            assertTrue(sql.contains("BEFORE UPDATE OR DELETE"));
            assertFalse(sql.contains("INSERT INTO fno_verified_nifty_daily_close"));
            assertFalse(sql.contains("INSERT INTO fno_verified_nifty_long_option_quote"));
        }
    }

    @Test
    void retentionDeletesExpiredOptionQuotesBeforeExpiredDailyCloses() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var source = new FnoVerifiedNiftyLongOptionSourceStore(jdbc);
        LocalDate today = LocalDate.of(2026, 11, 23);
        source.purgeExpired(today);
        var inOrder = inOrder(jdbc);
        inOrder.verify(jdbc).update(eq(
            "DELETE FROM fno_verified_nifty_long_option_quote WHERE retain_until < ?"),
            eq(java.sql.Date.valueOf(today)));
        inOrder.verify(jdbc).update(eq(
            "DELETE FROM fno_verified_nifty_daily_close WHERE retain_until < ?"),
            eq(java.sql.Date.valueOf(today)));
        inOrder.verifyNoMoreInteractions();
    }
}
