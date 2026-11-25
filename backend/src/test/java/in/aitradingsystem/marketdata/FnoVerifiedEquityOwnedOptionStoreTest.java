package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FnoVerifiedEquityOwnedOptionStoreTest {
    private static final String STOCK = "NSE_EQ|EXAMPLECO";
    private static final String PE = "NSE_FO|EXAMPLECO_20261127_95_PE";
    private static final String ACCOUNT = "private_owned_stocks";
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");

    @Test
    void equityDailyClosesNeedVerifiedNseCashCalendarAndOriginalAvailabilityRights() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedEquityOwnedOptionStore(jdbc);
        store.readCloses(STOCK, NOW);
        var call = mockingDetails(jdbc).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("query")).toList();
        assertEquals(1, call.size());
        Object[] args = call.getFirst().getArguments();
        String sql = (String) args[0];
        assertEquals(13, sql.chars().filter(c -> c == '?').count());
        assertEquals(15, args.length);
        assertEquals(STOCK,args[2]);
        assertTrue(sql.contains("nse_eq_trading_calendar"));
        assertTrue(sql.contains("nse_eq_completed_daily_close"));
        assertTrue(sql.contains("rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("e.first_available_at >= cal.session_close_at"));
        assertTrue(sql.contains("e.first_available_at <= ?"));
        assertTrue(sql.contains("cal.verified_at <= ?"));
        assertFalse(sql.contains("market_candle"));
    }

    @Test
    void ownedOptionReadRequiresExactAccountSettledSharesFiveIndependentResearchReceipts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedEquityOwnedOptionStore(jdbc);
        assertTrue(store.oneSettledLot(ACCOUNT, STOCK, PE, "PE", NOW).isEmpty());
        var call = mockingDetails(jdbc).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("query")).toList();
        assertEquals(1, call.size());
        Object[] args = call.getFirst().getArguments();
        String sql = (String) args[0];
        assertEquals(32, sql.chars().filter(c -> c == '?').count());
        assertEquals(34, args.length);
        assertEquals(ACCOUNT, args[2]);
        assertEquals(STOCK, args[3]);
        assertEquals(PE, args[4]);
        assertEquals("PE", args[5]);
        for (String receipt : new String[]{"holdings_rights","equity_rights",
                "option_rights","master_rights","cost_rights"}) {
            assertTrue(sql.contains(receipt + ".receipt_status = 'VERIFIED'"),receipt);
            assertTrue(sql.contains(receipt + ".permitted_use = 'MODEL_INFERENCE'"),receipt);
            assertTrue(sql.contains(receipt + ".verified_at <= ?"),receipt);
        }
        assertTrue(sql.contains("nse_eq_broker_settled_holdings"));
        assertTrue(sql.contains("nse_fo_stock_option_chain_depth_oi"));
        assertTrue(sql.contains("e.physical_delivery_match_verified"));
        assertTrue(sql.contains("e.settled_shares_broker_reconciled"));
        assertTrue(sql.contains("e.shares_deliverable_verified"));
        assertTrue(sql.contains("e.ownership_pledge_lien_absent"));
        assertTrue(sql.contains("e.holdings_source_observed_at <= ?"));
        assertTrue(sql.contains("e.option_first_observed_at <= ?"));
        assertTrue(sql.contains("e.equity_first_observed_at <= ?"));
        assertTrue(sql.contains("LIMIT 2"));
    }

    @Test
    void malformedBrokerAccountOrIndexOptionNeverAccessesPrivateHoldings() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedEquityOwnedOptionStore(jdbc);
        assertThrows(IllegalArgumentException.class, () ->
            store.oneSettledLot("", STOCK, PE, "PE", NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.oneSettledLot(ACCOUNT, "NSE_INDEX|Nifty 50", PE, "PE", NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.oneSettledLot(ACCOUNT, STOCK, "NSE_EQ|EXAMPLECO", "PE", NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.oneSettledLot(ACCOUNT, STOCK, PE, "CALL", NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.readCloses("NSE_INDEX|Nifty 50", NOW));
        verifyNoInteractions(jdbc);
    }

    @Test
    void originalDataSchemaContainsNoSeededHoldingsAndEnforcesLotAndRetention() throws Exception {
        try (var src = getClass().getResourceAsStream(
                "/db/migration/V109__fno_owned_equity_option_research_evidence.sql")) {
            assertNotNull(src);
            String ddl = new String(src.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(2, ddl.split("CREATE TABLE",-1).length-1);
            assertEquals(2, ddl.split("CREATE TRIGGER",-1).length-1);
            assertEquals(1, ddl.split("CREATE FUNCTION",-1).length-1);
            assertTrue(ddl.contains("settled_owned_shares = lot_size"));
            assertTrue(ddl.contains("account_scope VARCHAR(80)"));
            assertTrue(ddl.contains("ownership_pledge_lien_absent BOOLEAN NOT NULL"));
            assertTrue(ddl.contains("holdings_rights_receipt_id UUID NOT NULL"));
            assertTrue(ddl.contains("close_rights_receipt_id UUID NOT NULL"));
            assertTrue(ddl.contains("BEFORE UPDATE OR DELETE"));
            assertFalse(ddl.contains("INSERT INTO fno_verified_equity_owned_option"));
        }
    }

    @Test
    void expiredPrivateBrokerShareProofIsErasedBeforeExpiredDailyCloseHistory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedEquityOwnedOptionStore(jdbc);
        LocalDate today = LocalDate.of(2026, 11, 23);
        store.purgeExpired(today);
        var order = inOrder(jdbc);
        order.verify(jdbc).update(eq(
            "DELETE FROM fno_verified_equity_owned_option WHERE retain_until < ?"),
            eq(java.sql.Date.valueOf(today)));
        order.verify(jdbc).update(eq(
            "DELETE FROM fno_verified_equity_owned_daily_close WHERE retain_until < ?"),
            eq(java.sql.Date.valueOf(today)));
    }
}
