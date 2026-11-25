package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Source SQL/identity/digest tests; never actual market source verification. */
class FnoVerifiedStrategy207212SourceStoreTest {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    @Test void acceptsOnlySixExactRemainingStrategyIds(){
        var jdbc=mock(JdbcTemplate.class);
        var s=new FnoVerifiedStrategy207212SourceStore(jdbc);
        assertThrows(IllegalArgumentException.class,()->s.oneCurrent("strategy.STR-206",NOW));
        assertThrows(IllegalArgumentException.class,()->s.oneCurrent("strategy.STR-213",NOW));
        assertThrows(IllegalArgumentException.class,()->s.oneCurrent("strategy.STR-207",null));
        verifyNoInteractions(jdbc);
    }
    @Test void noRowsIsNotFabricatedAndSixIndependentRightsMandatory(){
        var jdbc=mock(JdbcTemplate.class);
        var s=new FnoVerifiedStrategy207212SourceStore(jdbc);
        for(int n=207;n<=212;n++)assertTrue(s.oneCurrent("strategy.STR-"+n,NOW).isEmpty());
        verify(jdbc,times(6)).query(argThat((String sql)->
            sql.contains("fno_verified_multistrategy_research_source")
            && sql.contains("s.strategy_id=? AND s.source_verified")
            && sql.contains("r_index.receipt_status='VERIFIED'")
            && sql.contains("r_options.receipt_status='VERIFIED'")
            && sql.contains("r_history.receipt_status='VERIFIED'")
            && sql.contains("r_greeks.receipt_status='VERIFIED'")
            && sql.contains("r_master.receipt_status='VERIFIED'")
            && sql.contains("r_auxiliary.receipt_status='VERIFIED'")
            && sql.contains("official_nifty_option_expiry_cutoff")
            && sql.contains("nse_fo_listed_futures")
            && sql.contains("nse_broker_confirmed_positions")
            && sql.contains("s.original_first_known_at<=?")
            && sql.contains("ORDER BY s.original_first_known_at DESC LIMIT 2")),
            any(RowMapper.class),any(Object[].class));
    }
    @Test void sha256MustMatchActualCanonicalSourceJsonbBytes() throws Exception {
        var s="{\"provider\": \"UPSTOX_ANALYTICS\"}";
        var sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(s.getBytes(StandardCharsets.UTF_8)));
        assertTrue(FnoVerifiedStrategy207212SourceStore.verifiedJsonbSha256(s,sha));
        assertFalse(FnoVerifiedStrategy207212SourceStore.verifiedJsonbSha256(s,"a".repeat(64)));
        assertFalse(FnoVerifiedStrategy207212SourceStore.verifiedJsonbSha256(s+" ",sha));
    }
    @Test void onlyExpiredEvidenceCanBePurged(){
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.update(anyString(),any(Date.class))).thenReturn(0);
        var s=new FnoVerifiedStrategy207212SourceStore(jdbc);
        assertEquals(0,s.purgeExpired(LocalDate.of(2026,11,22)));
        verify(jdbc).update(eq(
            "DELETE FROM fno_verified_multistrategy_research_source WHERE retain_until < ?"),
            eq(Date.valueOf(LocalDate.of(2026,11,22))));
    }
}
