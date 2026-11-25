package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FnoClosingSnapshotStoreTest {
    private static final LocalDate SESSION = LocalDate.of(2026, 11, 20);
    private static final FnoClosingSnapshotStore.Snapshot VALID = new FnoClosingSnapshotStore.Snapshot(
        "UPSTOX_ANALYTICS", "NSE_FO|SYN1001", "NSE_INDEX|Nifty 50", SESSION,
        Instant.parse("2026-11-20T09:59:58Z"), Instant.parse("2026-11-20T10:00:02Z"),
        new BigDecimal("24990"), new BigDecimal("24992"), 100000L, 5000L, SESSION.plusDays(30), true);

    @Test
    void persistenceIsOffByDefaultAndTouchesNoDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoClosingSnapshotStore(jdbc, false);
        assertEquals(FnoClosingSnapshotStore.Outcome.PERSISTENCE_DISABLED, store.save(VALID, true));
        verifyNoInteractions(jdbc);
    }

    @Test
    void unverifiedRightsFailClosedEvenWhenPersistenceEnabled() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoClosingSnapshotStore(jdbc, true);
        assertThrows(IllegalStateException.class, () -> store.save(VALID, false));
        verifyNoInteractions(jdbc);
    }

    @Test
    void validSnapshotIsWrittenWithFixedRightsBasis() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
        var store = new FnoClosingSnapshotStore(jdbc, true);
        assertEquals(FnoClosingSnapshotStore.Outcome.STORED, store.save(VALID, true));
        verify(jdbc).update(any(String.class), eq("UPSTOX_ANALYTICS"), eq("NSE_FO|SYN1001"),
            eq("NSE_INDEX|Nifty 50"), eq(java.sql.Date.valueOf(SESSION)), any(), any(),
            eq(new BigDecimal("24990")), eq(new BigDecimal("24992")), eq(100000L), eq(5000L),
            eq(FnoClosingSnapshotStore.RIGHTS_BASIS), eq(java.sql.Date.valueOf(SESSION.plusDays(30))));
    }

    @Test
    void duplicateNaturalKeyIsIgnoredNotOverwritten() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(0);
        var store = new FnoClosingSnapshotStore(jdbc, true);
        assertEquals(FnoClosingSnapshotStore.Outcome.DUPLICATE_IGNORED, store.save(VALID, true));
    }

    @Test
    void nonCanonicalProviderAndBadInputsAreRejectedBeforeWrite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoClosingSnapshotStore(jdbc, true);
        var lowerCase = new FnoClosingSnapshotStore.Snapshot("upstox_v3", VALID.instrumentKey(),
            VALID.underlyingKey(), SESSION, VALID.exchangeAt(), VALID.observedAt(),
            VALID.bid(), VALID.ask(), null, null, VALID.retainUntil(), true);
        assertThrows(IllegalArgumentException.class, () -> store.save(lowerCase, true));
        var inverted = new FnoClosingSnapshotStore.Snapshot(VALID.providerLabel(), VALID.instrumentKey(),
            VALID.underlyingKey(), SESSION, VALID.exchangeAt(), VALID.observedAt(),
            new BigDecimal("25"), new BigDecimal("24"), null, null, VALID.retainUntil(), true);
        assertThrows(IllegalArgumentException.class, () -> store.save(inverted, true));
        var tooLong = new FnoClosingSnapshotStore.Snapshot(VALID.providerLabel(), VALID.instrumentKey(),
            VALID.underlyingKey(), SESSION, VALID.exchangeAt(), VALID.observedAt(),
            VALID.bid(), VALID.ask(), null, null, SESSION.plusDays(366), true);
        assertThrows(IllegalArgumentException.class, () -> store.save(tooLong, true));
        verifyNoInteractions(jdbc);
    }

    @Test
    void unverifiedCloseIsNeverStored() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoClosingSnapshotStore(jdbc, true);
        var open = new FnoClosingSnapshotStore.Snapshot(VALID.providerLabel(), VALID.instrumentKey(),
            VALID.underlyingKey(), SESSION, VALID.exchangeAt(), VALID.observedAt(),
            VALID.bid(), VALID.ask(), null, null, VALID.retainUntil(), false);
        assertThrows(IllegalArgumentException.class, () -> store.save(open, true));
        verifyNoInteractions(jdbc);
    }

    @Test
    void retentionPurgeRunsAndReturnsDeletedRowCount() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(3);
        assertEquals(3, new FnoClosingSnapshotStore(jdbc, false).purgeExpired(SESSION));
    }

    @Test
    void oiReadIsExactContractReadOnlyAndFiltersRightsAndRetention() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoClosingSnapshotStore(jdbc, false);
        store.verifiedOiContractRows(VALID.instrumentKey(), SESSION.minusDays(10), SESSION);
        verify(jdbc).query(
            argThat((String sql) -> sql.contains("instrument_key = ?")
                && sql.contains("rights_basis = ?")
                && sql.contains("retain_until >= ?")
                && sql.contains("ORDER BY session_date, provider_label")),
            any(org.springframework.jdbc.core.RowMapper.class),
            eq(VALID.instrumentKey()),
            eq(java.sql.Date.valueOf(SESSION.minusDays(10))),
            eq(java.sql.Date.valueOf(SESSION)),
            eq(FnoClosingSnapshotStore.RIGHTS_BASIS), any(java.sql.Date.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void oiReadRejectsNonFuturesAndInvalidDateScopeBeforeAnySql() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new FnoClosingSnapshotStore(jdbc, false);
        assertThrows(IllegalArgumentException.class, () ->
            store.verifiedOiContractRows("NSE_EQ|SYN1001", SESSION.minusDays(10), SESSION));
        assertThrows(IllegalArgumentException.class, () ->
            store.verifiedOiContractRows(VALID.instrumentKey(), SESSION, SESSION.minusDays(1)));
        verifyNoInteractions(jdbc);
    }

}
