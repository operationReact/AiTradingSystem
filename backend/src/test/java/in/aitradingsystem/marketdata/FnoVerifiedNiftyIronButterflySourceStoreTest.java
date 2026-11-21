package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class FnoVerifiedNiftyIronButterflySourceStoreTest {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final String LP="NSE_FO|TEST_80_PE";
    static final String SP="NSE_FO|TEST_100_PE";
    static final String SC="NSE_FO|TEST_100_CE";
    static final String LC="NSE_FO|TEST_120_CE";

    @Test void blankDuplicatedOrMalformedKeysFailBeforeSql() {
        var jdbc=mock(JdbcTemplate.class);
        var s=new FnoVerifiedNiftyIronButterflySourceStore(jdbc);
        assertThrows(IllegalArgumentException.class,()->s.oneCurrent("",SP,SC,LC,NOW));
        assertThrows(IllegalArgumentException.class,()->s.oneCurrent(LP,LP,SC,LC,NOW));
        assertThrows(IllegalArgumentException.class,()->s.oneCurrent(LP,SP,SC,LC,null));
        verifyNoInteractions(jdbc);
    }
    @Test void absentRealProviderSourceReturnsEmptyWithoutAnyFabrication() {
        var jdbc=mock(JdbcTemplate.class);
        var s=new FnoVerifiedNiftyIronButterflySourceStore(jdbc);
        assertTrue(s.oneCurrent(LP,SP,SC,LC,NOW).isEmpty());
        verify(jdbc).query(argThat((String sql)->
            sql.contains("s.long_put_key=? AND s.short_put_key=?")
            && sql.contains("s.short_call_key=? AND s.long_call_key=?")
            && sql.contains("s.instrument_master_verified")
            && sql.contains("s.nonduplicated_four_leg_fees_verified")
            && sql.contains("s.short_put_strike=s.short_call_strike")
            && sql.contains("s.max_atm_distance_bps")
            && sql.contains("long_put_iv_rights.receipt_status='VERIFIED'")
            && sql.contains("short_put_iv_rights.receipt_status='VERIFIED'")
            && sql.contains("short_call_iv_rights.receipt_status='VERIFIED'")
            && sql.contains("long_call_iv_rights.receipt_status='VERIFIED'")
            && sql.contains("s.long_put_iv_source_sha256<>s.short_put_iv_source_sha256")
            && sql.contains("s.first_known_at<=?")
            && sql.contains("ORDER BY s.first_known_at DESC LIMIT 2")),
            any(RowMapper.class),any(Object[].class));
    }
    @Test void retentionPurgesOnlyExpiredObservations() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.update(anyString(),any(Date.class))).thenReturn(0);
        var s=new FnoVerifiedNiftyIronButterflySourceStore(jdbc);
        assertEquals(0,s.purgeExpired(LocalDate.of(2026,11,22)));
        verify(jdbc).update(eq(
            "DELETE FROM fno_verified_nifty_iron_butterfly_source WHERE retain_until < ?"),
            eq(Date.valueOf(LocalDate.of(2026,11,22))));
    }
}
