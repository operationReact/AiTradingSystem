package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FnoVerifiedCompletedOhlcvOiStoreTest {
    private static final String FUTURE = "NSE_FO|DEC_NIFTY_176";
    private static final Instant AS_OF = Instant.parse("2026-11-23T05:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 11, 2);
    private static final LocalDate TO = LocalDate.of(2026, 11, 23);

    @Test
    void independentlyVerifiedOhlcvAndOiRightsCalendarAndAsOfAreMandatory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedCompletedOhlcvOiStore(jdbc);
        store.readExactContract(FUTURE, FROM, TO, AS_OF);
        var calls = mockingDetails(jdbc).getInvocations().stream()
            .filter(x -> "query".equals(x.getMethod().getName())).toList();
        assertEquals(1, calls.size());
        Object[] args = calls.getFirst().getArguments(); // expanded JDBC varargs
        String sql = (String) args[0];
        assertEquals(20, args.length);
        assertEquals(18, sql.chars().filter(c -> c == '?').count());
        assertEquals(FUTURE, args[2]);
        assertEquals(java.sql.Date.valueOf(FROM), args[3]);
        assertEquals(java.sql.Date.valueOf(TO), args[4]);
        assertTrue(sql.contains("fno_verified_completed_ohlcv_oi_session"));
        assertTrue(sql.contains("nse_fo_completed_ohlcv"));
        assertTrue(sql.contains("nse_fo_final_oi"));
        assertTrue(sql.contains("nse_fo_trading_calendar"));
        assertTrue(sql.contains("p.source_kind = 'EXTERNAL'"));
        assertTrue(sql.contains("ohlcv_rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("oi_rights.receipt_status = 'VERIFIED'"));
        assertTrue(sql.contains("ohlcv_rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("oi_rights.permitted_use = 'MODEL_INFERENCE'"));
        assertTrue(sql.contains("s.ohlcv_first_available_at >= cal.session_close_at"));
        assertTrue(sql.contains("s.oi_first_available_at >= cal.session_close_at"));
        assertTrue(sql.contains("s.ohlcv_first_available_at <= ?"));
        assertTrue(sql.contains("s.oi_first_available_at <= ?"));
        assertTrue(sql.contains("s.retain_until >= ?"));
        assertTrue(sql.contains("s.captured_at <= ?"));
        assertTrue(sql.contains("cal.verified_at <= ?"));
        assertFalse(sql.toUpperCase().contains("INSERT INTO"));
        assertFalse(sql.contains("market_candle"));
        assertFalse(sql.contains("fno_closing_snapshot"));
    }

    @Test
    void replayReaderUsesSameOriginalRightsAndAsOfSqlPlusThreeSourceDigests() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedCompletedOhlcvOiStore(jdbc);
        store.readExactReplayContract(FUTURE, FROM, TO, AS_OF);
        var query = mockingDetails(jdbc).getInvocations().stream()
            .filter(x -> "query".equals(x.getMethod().getName())).findFirst().orElseThrow();
        Object[] args = query.getArguments();
        assertEquals(20, args.length);
        String sql = (String) args[0];
        assertEquals(18, sql.chars().filter(c -> c == '?').count());
        for (String gate : java.util.List.of(
                "s.ohlcv_source_digest",
                "s.oi_source_digest",
                "s.listing_source_digest",
                "ohlcv_rights.receipt_status = 'VERIFIED'",
                "oi_rights.receipt_status = 'VERIFIED'",
                "ohlcv_rights.permitted_use = 'MODEL_INFERENCE'",
                "oi_rights.permitted_use = 'MODEL_INFERENCE'",
                "s.captured_at <= ?",
                "s.ohlcv_first_available_at <= ?",
                "s.oi_first_available_at <= ?",
                "cal.first_available_at <= ?",
                "cal.verified_at <= ?",
                "cal.session_status = 'TRADING'"))
            assertTrue(sql.contains(gate), gate);
        assertFalse(sql.toUpperCase().contains("INSERT INTO"));
        assertFalse(sql.contains("market_candle"));
        assertFalse(sql.contains("fno_closing_snapshot"));
    }

    @Test
    void invalidContractDatesOrLookbackAreRejectedBeforeDatabaseQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedCompletedOhlcvOiStore(jdbc);
        assertThrows(IllegalArgumentException.class, () ->
            store.readExactContract("NSE_EQ|STOCK", FROM, TO, AS_OF));
        assertThrows(IllegalArgumentException.class, () ->
            store.readExactContract(FUTURE, TO, FROM, AS_OF));
        assertThrows(IllegalArgumentException.class, () ->
            store.readExactContract(FUTURE, FROM.minusDays(20), TO, AS_OF));
        assertThrows(IllegalArgumentException.class, () ->
            store.readExactContract(FUTURE, FROM, TO.plusDays(1), AS_OF));
        verifyNoInteractions(jdbc);
    }

    @Test
    void verifiedSourceCustodyContainsSeparateFirstAvailabilityEvidenceAndNoSeeds()
            throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V102__fno_verified_completed_ohlcv_oi_session.sql")) {
            assertNotNull(input);
            String schema = new String(input.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(schema.contains("CREATE TABLE fno_verified_completed_ohlcv_oi_session"));
            assertTrue(schema.contains("ohlcv_first_available_at TIMESTAMPTZ NOT NULL"));
            assertTrue(schema.contains("oi_first_available_at TIMESTAMPTZ NOT NULL"));
            assertTrue(schema.contains("listing_source_digest CHAR(64)"));
            assertTrue(schema.contains("ohlcv_rights_receipt_id UUID NOT NULL"));
            assertTrue(schema.contains("oi_rights_receipt_id UUID NOT NULL"));
            assertTrue(schema.contains("calendar_evidence_id UUID NOT NULL"));
            assertTrue(schema.contains("BEFORE UPDATE OR DELETE"));
            assertFalse(schema.contains("INSERT INTO fno_verified_completed_ohlcv_oi_session"));
        }
    }


    @Test
    void str176OriginalSourceInventoryIsRightsJoinedAndAsOfBoundedNotCertified() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var store=new FnoVerifiedCompletedOhlcvOiStore(jdbc);
        store.inspectHistoricalEligibility(AS_OF);
        var calls=mockingDetails(jdbc).getInvocations().stream()
            .filter(x -> "queryForObject".equals(x.getMethod().getName())).toList();
        assertEquals(1,calls.size());
        Object[] args=calls.getFirst().getArguments();
        String sql=(String)args[0];
        assertEquals(19,sql.chars().filter(c -> c=='?').count());
        assertEquals(21,args.length);
        assertEquals(java.sql.Date.valueOf(AS_OF.atZone(
            java.time.ZoneId.of("Asia/Kolkata")).toLocalDate().minusDays(26)),args[2]);
        assertEquals(java.sql.Date.valueOf(AS_OF.atZone(
            java.time.ZoneId.of("Asia/Kolkata")).toLocalDate().minusDays(1)),args[3]);
        for(String gate:java.util.List.of(
                "fno_verified_completed_ohlcv_oi_session",
                "p.source_kind = 'EXTERNAL'",
                "ohlcv_rights.receipt_status = 'VERIFIED'",
                "oi_rights.receipt_status = 'VERIFIED'",
                "ohlcv_rights.permitted_use = 'MODEL_INFERENCE'",
                "oi_rights.permitted_use = 'MODEL_INFERENCE'",
                "ohlcv_rights.verified_at <= ?",
                "oi_rights.verified_at <= ?",
                "ohlcv_rights.captured_at <= ?",
                "oi_rights.captured_at <= ?",
                "cal.session_status = 'TRADING'",
                "cal.first_available_at <= ?",
                "cal.verified_at <= ?",
                "cal.captured_at <= ?",
                "s.captured_at <= ?",
                "s.ohlcv_first_available_at <= ?",
                "s.oi_first_available_at <= ?",
                "s.retain_until >= ?",
                "HAVING COUNT(DISTINCT session_date) >= 7"))
            assertTrue(sql.contains(gate),gate);
        assertFalse(sql.toUpperCase().contains("INSERT INTO"));
        assertFalse(sql.toUpperCase().contains("UPDATE "));
        assertFalse(sql.toUpperCase().contains("DELETE FROM"));
        assertFalse(sql.contains("market_candle"));
        assertFalse(sql.contains("fno_closing_snapshot"));
    }

    @Test
    void expiredResearchRowsAreErasedOnlyAfterRetentionDeadline() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoVerifiedCompletedOhlcvOiStore(jdbc);
        store.purgeExpired(TO);
        verify(jdbc).update(eq(
            "DELETE FROM fno_verified_completed_ohlcv_oi_session WHERE retain_until < ?"),
            eq(java.sql.Date.valueOf(TO)));
        verifyNoMoreInteractions(jdbc);
    }
}
