package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Original calendar and source-rights SQL tests. No exchange APIs. */
class FnoVerifiedNiftyVolCrushSourceStoreTest {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    @Test void exactEventKeyAndAsOfRequiredBeforeSql(){
        var jdbc=mock(JdbcTemplate.class);
        var store=new FnoVerifiedNiftyVolCrushSourceStore(jdbc);
        assertThrows(IllegalArgumentException.class,()->store.oneCurrent("",NOW));
        assertThrows(IllegalArgumentException.class,()->store.oneCurrent("RBI_TEST",null));
        verifyNoInteractions(jdbc);
    }
    @Test void noVerifiedOfficialEventNeverFabricatesPrePostQuotes(){
        var jdbc=mock(JdbcTemplate.class);
        var store=new FnoVerifiedNiftyVolCrushSourceStore(jdbc);
        assertTrue(store.oneCurrent("RBI_TEST_20261120",NOW).isEmpty());
        verify(jdbc).query(argThat((String sql)->
            sql.contains("fno_nifty_vol_crush_event_source")
            && sql.contains("s.event_key=?")
            && sql.contains("s.original_event_verified")
            && sql.contains("s.pre_iv_original_verified")
            && sql.contains("s.post_iv_original_verified")
            && sql.contains("calendar_rights.receipt_status='VERIFIED'")
            && sql.contains("normalization_rights.receipt_status='VERIFIED'")
            && sql.contains("s.pre_iv_sha256<>s.post_iv_sha256")
            && sql.contains("s.event_end<=?")
            && sql.contains("ORDER BY s.first_known_at DESC LIMIT 2")),
            any(RowMapper.class),any(Object[].class));
    }
    @Test void archivedHistoricalAndEventCustodyAreSeparate(){
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.update(anyString(),any(Date.class))).thenReturn(0);
        var store=new FnoVerifiedNiftyVolCrushSourceStore(jdbc);
        assertEquals(0,store.purgeExpired(LocalDate.of(2026,11,22)));
        verify(jdbc).update(eq(
            "DELETE FROM fno_nifty_vol_crush_history_source WHERE retain_until < ?"),
            eq(Date.valueOf(LocalDate.of(2026,11,22))));
    }
}
