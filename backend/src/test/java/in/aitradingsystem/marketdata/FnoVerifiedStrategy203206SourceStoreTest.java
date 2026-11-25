package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** SQL contract with mocked JDBC, NOT independent NSE source verification. */
class FnoVerifiedStrategy203206SourceStoreTest {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    @Test void onlyFourExactStrategyIdsCanReadEvidence(){
        var db=mock(JdbcTemplate.class);
        var store=new FnoVerifiedStrategy203206SourceStore(db);
        assertThrows(IllegalArgumentException.class,()->store.oneCurrent("strategy.STR-202",NOW));
        assertThrows(IllegalArgumentException.class,()->store.oneCurrent("strategy.STR-207",NOW));
        assertThrows(IllegalArgumentException.class,()->store.oneCurrent("strategy.STR-203",null));
        verifyNoInteractions(db);
    }
    @Test void emptySourceAndSixSeparateOriginalRightsMustFailClosed(){
        var db=mock(JdbcTemplate.class);
        var store=new FnoVerifiedStrategy203206SourceStore(db);
        for(int i=203;i<=206;i++){
            assertEquals(Optional.empty(),store.oneCurrent("strategy.STR-"+i,NOW));
        }
        verify(db,times(4)).query(argThat((String sql)->
            sql.contains("fno_verified_multistrategy_research_source")
            && sql.contains("s.strategy_id=? AND s.source_verified")
            && sql.contains("r_index.receipt_status='VERIFIED'")
            && sql.contains("r_options.receipt_status='VERIFIED'")
            && sql.contains("r_history.receipt_status='VERIFIED'")
            && sql.contains("r_greeks.receipt_status='VERIFIED'")
            && sql.contains("r_master.receipt_status='VERIFIED'")
            && sql.contains("r_auxiliary.receipt_status='VERIFIED'")
            && sql.contains("r_auxiliary.permitted_use='MODEL_INFERENCE'")
            && sql.contains("s.original_first_known_at<=?")
            && sql.contains("ORDER BY s.original_first_known_at DESC LIMIT 2")),
            any(RowMapper.class),any(Object[].class));
    }
    @Test void claimedDigestMustActuallyMatchOriginalCanonicalJsonbText() throws Exception {
        var value="{\"provider\": \"UPSTOX_ANALYTICS\"}";
        var bytes=java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var digest=java.util.HexFormat.of().formatHex(bytes);
        assertTrue(FnoVerifiedStrategy203206SourceStore.verifiedJsonbSha256(value,digest));
        assertFalse(FnoVerifiedStrategy203206SourceStore.verifiedJsonbSha256(
            value,"a".repeat(64)));
        assertFalse(FnoVerifiedStrategy203206SourceStore.verifiedJsonbSha256(
            value+" ",digest));
    }
    @Test void onlyExpiredImmutableEvidenceCanBeDeleted(){
        var db=mock(JdbcTemplate.class);
        when(db.update(anyString(),any(Date.class))).thenReturn(0);
        var store=new FnoVerifiedStrategy203206SourceStore(db);
        assertEquals(0,store.purgeExpired(LocalDate.of(2026,11,22)));
        verify(db).update(eq(
            "DELETE FROM fno_verified_multistrategy_research_source WHERE retain_until < ?"),
            eq(Date.valueOf(LocalDate.of(2026,11,22))));
    }
}
