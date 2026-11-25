package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** SQL rights/retention tests, not broker or original NSE market validation. */
class FnoVerifiedNiftyIvRankSourceStoreTest {
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    @Test void absentLicensedThirtyDayIvSourceNeverSynthesizesHistory(){
        var jdbc=mock(JdbcTemplate.class);
        var store=new FnoVerifiedNiftyIvRankSourceStore(jdbc);
        assertTrue(store.oneCurrent(NOW).isEmpty());
        verify(jdbc).query(argThat((String sql)->
            sql.contains("fno_nifty_30d_iv_rank_current_source")
            && sql.contains("current_iv_rights.receipt_status='VERIFIED'")
            && sql.contains("normalization_rights.receipt_status='VERIFIED'")
            && sql.contains("s.constant_tenor_days=30")
            && sql.contains("ORDER BY s.first_known_at DESC LIMIT 2")),
            any(RowMapper.class),any(Object[].class));
    }
    @Test void nullAsOfIsRejectedBeforeAnyQuery(){
        var jdbc=mock(JdbcTemplate.class);
        var store=new FnoVerifiedNiftyIvRankSourceStore(jdbc);
        assertThrows(NullPointerException.class,()->store.oneCurrent(null));
        verifyNoInteractions(jdbc);
    }
    @Test void historicAndCurrentRetentionAreIndependent(){
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.update(anyString(),any(Date.class))).thenReturn(0);
        var store=new FnoVerifiedNiftyIvRankSourceStore(jdbc);
        assertEquals(0,store.purgeExpired(LocalDate.of(2026,11,22)));
        verify(jdbc).update(eq("DELETE FROM fno_nifty_30d_iv_rank_history_source WHERE retain_until < ?"),
            eq(Date.valueOf(LocalDate.of(2026,11,22))));
        verify(jdbc).update(eq("DELETE FROM fno_nifty_30d_iv_rank_current_source WHERE retain_until < ?"),
            eq(Date.valueOf(LocalDate.of(2026,11,22))));
    }
}
