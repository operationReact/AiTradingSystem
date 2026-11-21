package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FnoVerifiedFinalPriceOiSessionStoreTest {
    private static final Instant AS_OF = Instant.parse("2026-11-23T05:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 11, 2);
    private static final LocalDate TO = LocalDate.of(2026, 11, 23);
    private static final String CONTRACT = "NSE_FO|FUTDEC123";

    @Test
    void onlyImmutableFinalPriceOiReceiptsAndBothIndependentResearchRightsCanBeRead() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedFinalPriceOiSessionStore(jdbc);
        store.readExactContract(CONTRACT, FROM, TO, AS_OF);
        var reads = mockingDetails(jdbc).getInvocations().stream()
            .filter(i -> "query".equals(i.getMethod().getName())).toList();
        assertEquals(1, reads.size());
        String sql = reads.getFirst().getArgument(0, String.class);
        assertTrue(sql.contains("fno_verified_final_price_oi_session"));
        assertTrue(sql.contains("JOIN golden_data_provider"));
        assertTrue(sql.contains("p.source_kind = 'EXTERNAL'"));
        assertTrue(sql.contains("nse_fo_final_price"));
        assertTrue(sql.contains("nse_fo_final_oi"));
        assertTrue(sql.contains("nse_fo_trading_calendar"));
        assertTrue(sql.contains("price_rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("oi_rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("cal.session_status = 'TRADING'"));
        assertTrue(sql.contains("price_rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("oi_rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("s.retain_until >= ?"));
        assertTrue(sql.contains("s.recorded_at <= ?"));
        assertTrue(sql.contains("first_available_at <= ?"));
        assertTrue(sql.contains("final_price_first_available_at <= ?"));
        assertTrue(sql.contains("finalized_oi_first_available_at <= ?"));
        assertTrue(sql.contains("s.instrument_key = ?"));
        assertTrue(sql.contains("ORDER BY s.session_date, s.provider_label"));
        assertFalse(sql.toLowerCase().contains(" bid "));
        assertFalse(sql.toLowerCase().contains(" ask "));
        assertFalse(sql.toLowerCase().contains("market_candle"));
        assertFalse(sql.toLowerCase().contains(" on conflict "));
        assertEquals(18, sql.chars().filter(c -> c == '?').count());
        Object[] bound = reads.getFirst().getArguments(); // Mockito expands JDBC varargs
        assertEquals(20, bound.length);
        assertEquals(CONTRACT, bound[2]);
        assertEquals(java.sql.Date.valueOf(FROM), bound[3]);
        assertEquals(java.sql.Date.valueOf(TO), bound[4]);
    }

    @Test
    void badContractFutureDateAndOverwideReadFailBeforeDatabaseCall() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedFinalPriceOiSessionStore(jdbc);
        assertThrows(IllegalArgumentException.class, () ->
            store.readExactContract("NSE_EQ|STOCK", FROM, TO, AS_OF));
        assertThrows(IllegalArgumentException.class, () ->
            store.readExactContract(CONTRACT, TO, FROM, AS_OF));
        assertThrows(IllegalArgumentException.class, () ->
            store.readExactContract(CONTRACT, FROM, TO.plusDays(1), AS_OF));
        assertThrows(IllegalArgumentException.class, () ->
            store.readExactContract(CONTRACT, FROM.minusDays(30), TO, AS_OF));
        assertThrows(IllegalArgumentException.class, () ->
            store.readExactContract(CONTRACT, FROM, TO, null));
        verifyNoInteractions(jdbc);
    }

    @Test
    void verifiedSessionCustodyHasNoSeededRowsNoMarketQuotePromotionAndIsImmutable() throws Exception {
        var resource = FnoVerifiedFinalPriceOiSessionStoreTest.class.getResourceAsStream(
            "/db/migration/V100__fno_verified_final_price_oi_sessions.sql");
        assertNotNull(resource);
        String ddl;
        try (var stream = resource) {
            ddl = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(ddl.contains("CREATE TABLE fno_verified_final_price_oi_session"));
        assertTrue(ddl.contains("price_evidence_sha256 CHAR(64)"));
        assertTrue(ddl.contains("oi_evidence_sha256 CHAR(64)"));
        assertTrue(ddl.contains("golden_historical_exchange_session_evidence"));
        assertTrue(ddl.contains("golden_source_rights_receipt"));
        assertTrue(ddl.contains("BEFORE UPDATE OR DELETE"));
        assertFalse(ddl.contains("INSERT INTO fno_verified_final_price_oi_session"));
        assertFalse(ddl.contains("fno_closing_snapshot"));
    }
}
