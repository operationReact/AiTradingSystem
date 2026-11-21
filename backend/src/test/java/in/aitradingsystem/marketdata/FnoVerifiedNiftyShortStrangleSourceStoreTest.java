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

class FnoVerifiedNiftyShortStrangleSourceStoreTest {
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    private static final String ACCOUNT="personal_margin_account";
    private static final String CE="NSE_FO|NIFTY_20261127_105_CE";
    private static final String PE="NSE_FO|NIFTY_20261127_95_PE";

    @Test void unauthorizedOrAmbiguousKeysRejectedBeforeDbRead() {
        var jdbc=mock(JdbcTemplate.class);
        var store=new FnoVerifiedNiftyShortStrangleSourceStore(jdbc);
        assertThrows(IllegalArgumentException.class,()->store.oneCurrent(ACCOUNT,"",PE,NOW));
        assertThrows(IllegalArgumentException.class,()->store.oneCurrent(ACCOUNT,CE,CE,NOW));
        assertThrows(IllegalArgumentException.class,()->store.oneCurrent(ACCOUNT,CE,PE,null));
        assertThrows(IllegalArgumentException.class,()->store.oneCurrent("",CE,PE,NOW));
        verifyNoInteractions(jdbc);
    }
    @Test void noOriginalProviderQuoteYieldsNoResearchData() {
        var jdbc=mock(JdbcTemplate.class);
        var store=new FnoVerifiedNiftyShortStrangleSourceStore(jdbc);
        assertTrue(store.oneCurrent(ACCOUNT,CE,PE,NOW).isEmpty());
        verify(jdbc).query(
            argThat((String sql)->sql.contains("ce_iv_rights.source_domain='nse_fo_option_implied_volatility'")
                && sql.contains("pe_iv_rights.source_domain='nse_fo_option_implied_volatility'")
                && sql.contains("s.ce_iv_source_sha256<>s.pe_iv_source_sha256")
                && sql.contains("s.captured_at<=?")
                && sql.contains("s.instrument_master_verified")
                && sql.contains("s.ce_strike") && sql.contains("s.pe_strike")
                && sql.contains("s.max_otm_distance_bps")
                && sql.contains("s.account_scope=?")
                && sql.contains("s.option_writing_permission_verified")
                && sql.contains("s.unbounded_call_tail_risk_reviewed")
                && sql.contains("s.independently_reconciled_broker_margin")
                && sql.contains("margin_rights.receipt_status='VERIFIED'")
                && sql.contains("ce_bid_depth_lots")
                && sql.contains("pe_bid_depth_lots")
                && sql.contains("receipt_status='VERIFIED'")
                && sql.contains("ORDER BY s.first_known_at DESC LIMIT 2")),
            any(RowMapper.class),any(Object[].class));
    }
    @Test void retentionOnlyRemovesPreviouslyExpiredSourceObservations() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.update(anyString(),any(Date.class))).thenReturn(0);
        var store=new FnoVerifiedNiftyShortStrangleSourceStore(jdbc);
        assertEquals(0,store.purgeExpired(LocalDate.of(2026,11,21)));
        verify(jdbc).update(eq(
            "DELETE FROM fno_verified_nifty_short_strangle_iv_source WHERE retain_until < ?"),
            eq(Date.valueOf(LocalDate.of(2026,11,21))));
    }
}
