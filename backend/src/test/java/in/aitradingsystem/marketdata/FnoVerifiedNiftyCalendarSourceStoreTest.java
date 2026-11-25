package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** No network or market credentials; original SQL contract tests only. */
class FnoVerifiedNiftyCalendarSourceStoreTest {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final String NEAR="NSE_FO|TEST_NEAR_100_CE";
    static final String FAR="NSE_FO|TEST_FAR_100_CE";
    @Test void sameContractOrMissingTypeBlockedBeforeSql(){
        var jdbc=mock(JdbcTemplate.class);var s=new FnoVerifiedNiftyCalendarSourceStore(jdbc);
        assertThrows(IllegalArgumentException.class,()->s.oneCurrent(NEAR,NEAR,"CE",NOW));
        assertThrows(IllegalArgumentException.class,()->s.oneCurrent(NEAR,FAR,"X",NOW));
        assertThrows(IllegalArgumentException.class,()->s.oneCurrent("",FAR,"CE",NOW));
        verifyNoInteractions(jdbc);
    }
    @Test void emptySourceNeedsIndependentNearFarIvAndRealDepth(){
        var jdbc=mock(JdbcTemplate.class);var s=new FnoVerifiedNiftyCalendarSourceStore(jdbc);
        assertTrue(s.oneCurrent(NEAR,FAR,"CE",NOW).isEmpty());
        verify(jdbc).query(argThat((String sql)->
            sql.contains("s.near_key=? AND s.far_key=? AND s.option_type=?")
            && sql.contains("s.near_iv_source_sha256<>s.far_iv_source_sha256")
            && sql.contains("s.near_bid_depth_lots>=s.min_depth_lots")
            && sql.contains("s.far_ask_depth_lots>=s.min_depth_lots")
            && sql.contains("near_iv_rights.receipt_status='VERIFIED'")
            && sql.contains("far_iv_rights.receipt_status='VERIFIED'")
            && sql.contains("s.original_two_leg_fees_verified")
            && sql.contains("ORDER BY s.first_known_at DESC LIMIT 2")),
            any(RowMapper.class),any(Object[].class));
    }
    @Test void onlyExpiredEvidenceIsPurgeable(){
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.update(anyString(),any(Date.class))).thenReturn(0);
        var s=new FnoVerifiedNiftyCalendarSourceStore(jdbc);
        assertEquals(0,s.purgeExpired(LocalDate.of(2026,11,22)));
        verify(jdbc).update(eq("DELETE FROM fno_verified_nifty_calendar_source WHERE retain_until < ?"),
            eq(Date.valueOf(LocalDate.of(2026,11,22))));
    }
}
