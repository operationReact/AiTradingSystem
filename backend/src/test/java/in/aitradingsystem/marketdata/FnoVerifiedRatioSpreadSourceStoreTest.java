package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FnoVerifiedRatioSpreadSourceStoreTest {
    private static final String ACCOUNT="private_ratio_account";
    private static final String LONG="NSE_FO|NIFTY_20261127_120_CE";
    private static final String SHORT="NSE_FO|NIFTY_20261127_130_CE";
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");

    @Test void sourceRequiresExactAccountTwoShortLotsOriginalTimeAndFiveResearchRights(){
        var jdbc=mock(JdbcTemplate.class);
        var store=new FnoVerifiedRatioSpreadSourceStore(jdbc);
        assertTrue(store.oneCurrent(ACCOUNT,"CALL",LONG,SHORT,NOW).isEmpty());
        var query=mockingDetails(jdbc).getInvocations().stream()
            .filter(i->i.getMethod().getName().equals("query")).toList();
        assertEquals(1,query.size());
        Object[] args=query.getFirst().getArguments();
        String sql=(String)args[0];
        assertEquals(sql.chars().filter(ch->ch=='?').count()+2,args.length);
        assertEquals(ACCOUNT,args[2]);
        assertEquals("CALL",args[3]);
        assertEquals(LONG,args[4]);
        assertEquals(SHORT,args[5]);
        assertEquals(java.sql.Date.valueOf(LocalDate.of(2026,11,20)),args[9]);
        assertTrue(sql.contains("s.independently_reconciled_margin"));
        assertTrue(sql.contains("s.true_two_short_lots_depth"));
        assertTrue(sql.contains("s.three_leg_fees_not_double_counted"));
        assertTrue(sql.contains("nse_nifty_underlying_tick"));
        assertTrue(sql.contains("nse_fo_option_chain_depth_oi"));
        assertTrue(sql.contains("nse_fo_option_instrument_master"));
        assertTrue(sql.contains("nse_fo_ratio_3leg_fees_margin"));
        for(var prefix:new String[]{"index_rights","long_rights",
                "short_rights","master_rights","cost_rights"}){
            assertTrue(sql.contains(prefix+".receipt_status='VERIFIED'"));
            assertTrue(sql.contains(prefix+".permitted_use='MODEL_INFERENCE'"));
            assertTrue(sql.contains(prefix+".verified_at<=?"));
        }
        assertTrue(sql.contains("s.first_known_at<=?"));
        assertTrue(sql.contains("s.captured_at<=?"));
        assertTrue(sql.contains("s.valid_until>=?"));
        assertTrue(sql.contains("LIMIT 2"));
        assertFalse(sql.contains("market_candle"));
    }
    @Test void invalidAccountOrDuplicateOrWrongOptionKeysFailBeforeSql(){
        var jdbc=mock(JdbcTemplate.class);
        var store=new FnoVerifiedRatioSpreadSourceStore(jdbc);
        assertThrows(IllegalArgumentException.class,
            ()->store.oneCurrent("","CALL",LONG,SHORT,NOW));
        assertThrows(IllegalArgumentException.class,
            ()->store.oneCurrent(ACCOUNT,"LONG",LONG,SHORT,NOW));
        assertThrows(IllegalArgumentException.class,
            ()->store.oneCurrent(ACCOUNT,"CALL",LONG,LONG,NOW));
        assertThrows(IllegalArgumentException.class,
            ()->store.oneCurrent(ACCOUNT,"CALL","NSE_EQ|STOCK",SHORT,NOW));
        verifyNoInteractions(jdbc);
    }
    @Test void emptyImmutableRatioSchemaEncodesUnboundedCallAndFinitePutEvidence()
            throws Exception {
        try(var is=getClass().getResourceAsStream(
                "/db/migration/V111__fno_verified_nifty_ratio_spread_source.sql")){
            assertNotNull(is);
            var ddl=new String(is.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(ddl.contains("CREATE TABLE fno_verified_nifty_ratio_spread_source"));
            assertTrue(ddl.contains("account_scope VARCHAR(80) NOT NULL"));
            assertTrue(ddl.contains("short_bid_depth_lots BIGINT NOT NULL"));
            assertTrue(ddl.contains("independently_reconciled_margin BOOLEAN NOT NULL"));
            assertTrue(ddl.contains("three_leg_fees_not_double_counted BOOLEAN NOT NULL"));
            assertTrue(ddl.contains("stress_tail_risk_reviewed BOOLEAN NOT NULL"));
            assertTrue(ddl.contains("margin_cost_rights_receipt_id UUID NOT NULL"));
            assertTrue(ddl.contains("BEFORE UPDATE OR DELETE"));
            assertFalse(ddl.contains("INSERT INTO fno_verified_nifty_ratio_spread_source"));
        }
    }
    @Test void confidentialBrokerMarginAndRawDepthPurgeOnlyAfterRetentionExpiry(){
        var jdbc=mock(JdbcTemplate.class);
        var source=new FnoVerifiedRatioSpreadSourceStore(jdbc);
        var today=LocalDate.of(2026,11,23);
        source.purgeExpired(today);
        verify(jdbc).update(
            eq("DELETE FROM fno_verified_nifty_ratio_spread_source WHERE retain_until < ?"),
            eq(java.sql.Date.valueOf(today)));
    }
}
