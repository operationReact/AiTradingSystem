package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class UpstoxPersistedIngestionReceiptsTest {
    @Test
    void absentProviderIsUnknownRatherThanZero() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(false);
        when(jdbc.queryForObject(anyString(), any(ResultSetExtractor.class)))
                .thenAnswer(call -> ((ResultSetExtractor<?>) call.getArgument(1)).extractData(rows));
        var result = new UpstoxPersistedIngestionReceipts(jdbc).snapshot();
        assertEquals("NO_PROVIDER_RECEIPT", result.evidenceState());
        assertNull(result.jobs());
        assertNull(result.verifiedRecords());
        assertNull(result.attempts());
    }

    @Test
    void registeredProviderWithNoJobsIsObservedZeroNotRunning() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true);
        when(rows.getLong(anyString())).thenReturn(0L);
        when(jdbc.queryForObject(anyString(), any(ResultSetExtractor.class)))
                .thenAnswer(call -> ((ResultSetExtractor<?>) call.getArgument(1)).extractData(rows));
        var result = new UpstoxPersistedIngestionReceipts(jdbc).snapshot();
        assertEquals("PERSISTED_CHECKPOINTS_ONLY", result.evidenceState());
        assertEquals(0L, result.jobs());
        assertEquals(0L, result.verifiedRecords());
        assertNull(result.lastCheckpointAt());
    }

    @Test
    void countsAreProviderScopedAndOnlyVerifiedRowsContributeToRecords() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true);
        when(rows.getLong("jobs")).thenReturn(2L);
        when(rows.getLong("chunks")).thenReturn(8L);
        when(rows.getLong("attempts")).thenReturn(12L);
        when(rows.getLong("verified_chunks")).thenReturn(3L);
        when(rows.getLong("verified_records")).thenReturn(320L);
        when(rows.getLong("failed_chunks")).thenReturn(1L);
        when(rows.getLong("retry_chunks")).thenReturn(2L);
        var cooldown = OffsetDateTime.of(2026, 11, 14, 1, 0, 0, 0, ZoneOffset.UTC);
        when(rows.getObject("cooldown_until", OffsetDateTime.class)).thenReturn(cooldown);
        when(jdbc.queryForObject(anyString(), any(ResultSetExtractor.class)))
                .thenAnswer(call -> {
                    String sql = call.getArgument(0);
                    assertTrue(sql.contains("provider_code = 'UPSTOX'"));
                    assertTrue(sql.contains("c.status = 'VERIFIED'"));
                    assertTrue(sql.contains("j.provider_id = p.provider_id"));
                    return ((ResultSetExtractor<?>) call.getArgument(1)).extractData(rows);
                });
        var result = new UpstoxPersistedIngestionReceipts(jdbc).snapshot();
        assertEquals(12L, result.attempts());
        assertEquals(320L, result.verifiedRecords());
        assertEquals(1L, result.failedChunks());
        assertEquals(2L, result.retryChunks());
        assertEquals(cooldown.toInstant(), result.cooldownUntil());
    }
}
