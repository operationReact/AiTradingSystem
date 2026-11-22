package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FnoVerifiedIntradayLiquidityStoreTest {
    private static final Instant NOW = Instant.parse("2026-11-23T05:00:00Z");
    private static final String CONTRACT = "NSE_FO|NIFTY_FUT_DEC";
    private static final LocalDate EXPIRY = LocalDate.of(2026, 12, 29);

    @Test
    void onlyThreeOriginalTimeFuturesSnapshotsWithQuoteOiAndVolumeResearchRights() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedIntradayLiquidityStore(jdbc);
        assertTrue(store.latestThreeFutures(CONTRACT, EXPIRY, 75, NOW).isEmpty());
        var queries = mockingDetails(jdbc).getInvocations().stream()
            .filter(i -> "query".equals(i.getMethod().getName())).toList();
        assertEquals(1, queries.size());
        Object[] args = queries.getFirst().getArguments();
        assertEquals(24, args.length);
        String sql = (String) args[0];
        assertEquals(22, sql.chars().filter(ch -> ch == '?').count());
        assertEquals(CONTRACT, args[2]);
        assertEquals(java.sql.Date.valueOf(EXPIRY), args[3]);
        assertEquals(75, args[4]);
        assertEquals(java.sql.Timestamp.from(NOW.minusSeconds(90)), args[5]);
        assertTrue(sql.contains("nse_fo_intraday_l1_depth"));
        assertTrue(sql.contains("nse_fo_intraday_oi"));
        assertTrue(sql.contains("nse_fo_intraday_volume"));
        assertTrue(sql.contains("quote_rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("oi_rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("volume_rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("quote_rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("oi_rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("volume_rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("s.first_observed_at <= ?"));
        assertTrue(sql.contains("s.oi_first_observed_at <= ?"));
        assertTrue(sql.contains("s.volume_first_observed_at <= ?"));
        assertTrue(sql.contains("s.captured_at <= ?"));
        assertTrue(sql.contains("s.retain_until >= ?"));
        assertTrue(sql.contains("ORDER BY s.exchange_at DESC"));
        assertTrue(sql.contains("LIMIT 3"));
        assertFalse(sql.contains("market_candle"));
        assertFalse(sql.contains("fno_closing_snapshot"));
    }

    @Test
    void replayReadRequiresOriginalThreeRightsAndFiveImmutableSourceDigests() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var store=new FnoVerifiedIntradayLiquidityStore(jdbc);
        assertTrue(store.latestThreeReplayFutures(CONTRACT,EXPIRY,75,NOW).isEmpty());
        var call=mockingDetails(jdbc).getInvocations().stream()
            .filter(x->"query".equals(x.getMethod().getName())).findFirst().orElseThrow();
        Object[] args=call.getArguments();
        assertEquals(24,args.length);
        String sql=(String)args[0];
        assertEquals(22,sql.chars().filter(c->c=='?').count());
        for(String proof:java.util.List.of("s.session_source_digest",
                "s.contract_source_digest","s.quote_source_digest",
                "s.oi_source_digest","s.volume_source_digest",
                "s.instrument_kind = 'FUTURE'",
                "s.exchange_session_verified = TRUE",
                "quote_rights.receipt_status = 'VERIFIED'",
                "oi_rights.receipt_status = 'VERIFIED'",
                "volume_rights.receipt_status = 'VERIFIED'",
                "quote_rights.verified_at <= ?",
                "oi_rights.verified_at <= ?",
                "volume_rights.verified_at <= ?",
                "s.first_observed_at <= ?",
                "s.oi_first_observed_at <= ?",
                "s.volume_first_observed_at <= ?",
                "s.captured_at <= ?","s.retain_until >= ?","LIMIT 3"))
            assertTrue(sql.contains(proof),proof);
        assertFalse(sql.toUpperCase().contains("INSERT INTO"));
        assertFalse(sql.contains("market_candle"));
    }

    @Test
    void sourceIsNeverInvokedForBadIdentityExpiryOrLot() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedIntradayLiquidityStore(jdbc);
        assertThrows(IllegalArgumentException.class, () ->
            store.latestThreeFutures("NSE_EQ|STOCK", EXPIRY, 75, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.latestThreeFutures(CONTRACT, LocalDate.of(2026, 11, 20), 75, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.latestThreeFutures(CONTRACT, EXPIRY, 0, NOW));
        assertThrows(IllegalArgumentException.class, () ->
            store.latestThreeFutures(CONTRACT, EXPIRY, 75, null));
        verifyNoInteractions(jdbc);
    }

    @Test
    void originalQuotesOiVolumeHaveIndependentCustodyAndZeroSeededRows()
            throws Exception {
        try (var data = getClass().getResourceAsStream(
                "/db/migration/V104__fno_verified_intraday_liquidity_evidence.sql")) {
            assertNotNull(data);
            String ddl = new String(data.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(ddl.contains("CREATE TABLE fno_verified_intraday_liquidity_evidence"));
            assertTrue(ddl.contains("instrument_kind VARCHAR(8)"));
            assertTrue(ddl.contains("oi_first_observed_at TIMESTAMPTZ NOT NULL"));
            assertTrue(ddl.contains("volume_first_observed_at TIMESTAMPTZ NOT NULL"));
            assertTrue(ddl.contains("quote_rights_receipt_id UUID NOT NULL"));
            assertTrue(ddl.contains("oi_rights_receipt_id UUID NOT NULL"));
            assertTrue(ddl.contains("volume_rights_receipt_id UUID NOT NULL"));
            assertTrue(ddl.contains("exchange_session_verified BOOLEAN NOT NULL"));
            assertTrue(ddl.contains("BEFORE UPDATE OR DELETE"));
            assertFalse(ddl.contains("INSERT INTO fno_verified_intraday_liquidity_evidence"));
        }
    }

    @Test
    void sourceTimeDepthOiAndVolumeReceiptsPurgeOnlyAfterRetentionExpiry() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedIntradayLiquidityStore(jdbc);
        var today = LocalDate.of(2026, 11, 23);
        store.purgeExpired(today);
        verify(jdbc).update(eq(
            "DELETE FROM fno_verified_intraday_liquidity_evidence WHERE retain_until < ?"),
            eq(java.sql.Date.valueOf(today)));
    }
}
