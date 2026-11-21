package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class FnoVerifiedNiftyLongButterflySourceStoreTest {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final String LOW="NSE_FO|TEST_90_CE";
    static final String MID="NSE_FO|TEST_100_CE";
    static final String HIGH="NSE_FO|TEST_110_CE";

    @Test void repeatedOrInvalidContractCannotReachDatabase(){
        var jdbc=mock(JdbcTemplate.class);
        var s=new FnoVerifiedNiftyLongButterflySourceStore(jdbc);
        assertThrows(IllegalArgumentException.class,()->s.oneCurrent(LOW,LOW,HIGH,NOW));
        assertThrows(IllegalArgumentException.class,()->s.oneCurrent("",MID,HIGH,NOW));
        assertThrows(IllegalArgumentException.class,()->s.oneCurrent(LOW,MID,HIGH,null));
        verifyNoInteractions(jdbc);
    }
    @Test void originalThreeIvQuotesAndTwoCenterSellLotsAreRequired(){
        var jdbc=mock(JdbcTemplate.class);
        var s=new FnoVerifiedNiftyLongButterflySourceStore(jdbc);
        assertTrue(s.oneCurrent(LOW,MID,HIGH,NOW).isEmpty());
        verify(jdbc).query(argThat((String sql)->
            sql.contains("s.center_bid_depth_lots>=2*s.min_depth_lots")
            && sql.contains("s.lower_ask_depth_lots>=s.min_depth_lots")
            && sql.contains("s.upper_ask_depth_lots>=s.min_depth_lots")
            && sql.contains("s.two_center_short_lots_verified")
            && sql.contains("s.four_contract_fees_verified")
            && sql.contains("lower_iv_rights.receipt_status='VERIFIED'")
            && sql.contains("center_iv_rights.receipt_status='VERIFIED'")
            && sql.contains("upper_iv_rights.receipt_status='VERIFIED'")
            && sql.contains("s.lower_iv_source_sha256<>s.center_iv_source_sha256")
            && sql.contains("ORDER BY s.first_known_at DESC LIMIT 2")),
            any(RowMapper.class),any(Object[].class));
    }
    @Test void onlyExpiredOriginalEvidenceCanBePurged(){
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.update(anyString(),any(Date.class))).thenReturn(0);
        var s=new FnoVerifiedNiftyLongButterflySourceStore(jdbc);
        assertEquals(0,s.purgeExpired(LocalDate.of(2026,11,22)));
        verify(jdbc).update(eq(
            "DELETE FROM fno_verified_nifty_long_butterfly_source WHERE retain_until < ?"),
            eq(Date.valueOf(LocalDate.of(2026,11,22))));
    }
}
