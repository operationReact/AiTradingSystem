package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FnoVerifiedOwnedEquityCollarCostStoreTest {
    private static final String ACCOUNT="private_owned_account";
    private static final String STOCK="NSE_EQ|EXAMPLECO";
    private static final String PE="NSE_FO|EXAMPLECO_20261127_95_PE";
    private static final String CE="NSE_FO|EXAMPLECO_20261127_110_CE";
    private static final LocalDate EXPIRY=LocalDate.of(2026,11,27);
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");

    @Test void independentFeeAndStockQuoteRightsRequireSameAccountAndOriginalAsOf(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var source=new FnoVerifiedOwnedEquityCollarCostStore(jdbc);
        assertTrue(source.oneCurrent(ACCOUNT,STOCK,PE,CE,EXPIRY,50,NOW).isEmpty());
        var queries=mockingDetails(jdbc).getInvocations().stream()
            .filter(i->i.getMethod().getName().equals("query")).toList();
        assertEquals(1,queries.size());
        Object[] args=queries.getFirst().getArguments();
        String sql=(String)args[0];
        assertEquals(19,sql.chars().filter(c->c=='?').count());
        assertEquals(21,args.length);
        assertEquals(ACCOUNT,args[2]);
        assertEquals(STOCK,args[3]);
        assertEquals(PE,args[4]);
        assertEquals(CE,args[5]);
        assertEquals(java.sql.Date.valueOf(EXPIRY),args[6]);
        assertEquals(50,args[7]);
        assertEquals(java.sql.Date.valueOf(LocalDate.of(2026,11,20)),args[12]);
        assertTrue(sql.contains("c.combined_fees_not_double_counted"));
        assertTrue(sql.contains("c.broker_settled_shares_reconciled"));
        assertTrue(sql.contains("c.physical_delivery_and_assignment_reviewed"));
        assertTrue(sql.contains("nse_eq_owned_stock_collar_all_in_fees"));
        assertTrue(sql.contains("nse_eq_quote_depth"));
        assertTrue(sql.contains("quote_rights.receipt_status='VERIFIED'"));
        assertTrue(sql.contains("fees.receipt_status='VERIFIED'"));
        assertTrue(sql.contains("quote_rights.permitted_use='MODEL_INFERENCE'"));
        assertTrue(sql.contains("fees.permitted_use='MODEL_INFERENCE'"));
        assertTrue(sql.contains("c.equity_first_known_at<=?"));
        assertTrue(sql.contains("c.captured_at<=?"));
        assertTrue(sql.contains("c.valid_until>=?"));
        assertTrue(sql.contains("LIMIT 2"));
        assertFalse(sql.contains("market_candle"));
    }
    @Test void noAccountOrStockOrSameOptionCannotQueryFinancialReceipt(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var store=new FnoVerifiedOwnedEquityCollarCostStore(jdbc);
        assertThrows(IllegalArgumentException.class,
            ()->store.oneCurrent("",STOCK,PE,CE,EXPIRY,50,NOW));
        assertThrows(IllegalArgumentException.class,
            ()->store.oneCurrent(ACCOUNT,"NSE_INDEX|Nifty 50",PE,CE,EXPIRY,50,NOW));
        assertThrows(IllegalArgumentException.class,
            ()->store.oneCurrent(ACCOUNT,STOCK,PE,PE,EXPIRY,50,NOW));
        assertThrows(IllegalArgumentException.class,
            ()->store.oneCurrent(ACCOUNT,STOCK,PE,CE,EXPIRY,0,NOW));
        verifyNoInteractions(jdbc);
    }
    @Test void schemaRequiresIndependentStockBidAskSourceAndZeroSeededRows() throws Exception {
        try(var stream=getClass().getResourceAsStream(
                "/db/migration/V110__fno_verified_owned_equity_collar_cost.sql")){
            assertNotNull(stream);
            String ddl=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(ddl.contains("CREATE TABLE fno_verified_owned_equity_collar_cost"));
            assertTrue(ddl.contains("equity_bid NUMERIC(20,6) NOT NULL"));
            assertTrue(ddl.contains("equity_ask NUMERIC(20,6) NOT NULL"));
            assertTrue(ddl.contains("equity_quote_rights_receipt_id UUID NOT NULL"));
            assertTrue(ddl.contains("combined_fees_not_double_counted BOOLEAN NOT NULL"));
            assertTrue(ddl.contains("broker_settled_shares_reconciled BOOLEAN NOT NULL"));
            assertTrue(ddl.contains("BEFORE UPDATE OR DELETE"));
            assertFalse(ddl.contains("INSERT INTO fno_verified_owned_equity_collar_cost"));
        }
    }
    @Test void privateFinancialProofOnlyPurgedAfterRetentionExpiry(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var source=new FnoVerifiedOwnedEquityCollarCostStore(jdbc);
        var day=LocalDate.of(2026,11,23);
        source.purgeExpired(day);
        verify(jdbc).update(
            eq("DELETE FROM fno_verified_owned_equity_collar_cost WHERE retain_until < ?"),
            eq(java.sql.Date.valueOf(day)));
    }
}
