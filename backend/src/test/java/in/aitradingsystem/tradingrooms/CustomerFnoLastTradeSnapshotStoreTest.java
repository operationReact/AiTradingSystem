package in.aitradingsystem.tradingrooms;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

class CustomerFnoLastTradeSnapshotStoreTest {
    @Test
    void disabledRetentionNeverReadsStreamOrDatabase() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<UpstoxFnoV3LiveFeedService> source =
            mock(ObjectProvider.class);
        var store = new CustomerFnoLastTradeSnapshotStore(
            db, source, false, Clock.systemUTC());
        store.captureFreshTrades();
        org.assertj.core.api.Assertions.assertThat(
            store.latest("NSE_FO|61001")).isEmpty();
        verifyNoInteractions(db, source);
    }

    @Test
    void authorizedFreshV3LastTradeIsRetainedButNeverUsedForExecution() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<UpstoxFnoV3LiveFeedService> provider =
            mock(ObjectProvider.class);
        UpstoxFnoV3LiveFeedService stream =
            mock(UpstoxFnoV3LiveFeedService.class);
        var now = Instant.parse("2026-11-23T04:30:02Z");
        var exchange = now.minusSeconds(2);
        when(provider.getIfAvailable()).thenReturn(stream);
        when(stream.eligibleDisplaySnapshot()).thenReturn(List.of(
            new UpstoxFnoV3LiveFeedService.DisplayQuote(
                "NSE_FO|61001", new BigDecimal("121.75"),
                new BigDecimal("118.00"), 3000L,
                null, null, null, null, null,
                null, null, exchange, now.minusSeconds(1))));
        var store = new CustomerFnoLastTradeSnapshotStore(
            db, provider, true, Clock.fixed(now, ZoneOffset.UTC));
        store.captureFreshTrades();
        verify(db).update(anyString(), eq("NSE_FO|61001"),
            any(), any(), eq(new BigDecimal("121.75")),
            eq(new BigDecimal("118.00")), eq(3000L), any());
        verify(stream, never()).eligiblePaperQuote(anyString());
    }

    @Test
    void expiredOrUnverifiedTradeCannotBeSaved() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<UpstoxFnoV3LiveFeedService> provider =
            mock(ObjectProvider.class);
        UpstoxFnoV3LiveFeedService stream =
            mock(UpstoxFnoV3LiveFeedService.class);
        var now = Instant.parse("2026-11-23T04:30:02Z");
        when(provider.getIfAvailable()).thenReturn(stream);
        when(stream.eligibleDisplaySnapshot()).thenReturn(List.of(
            new UpstoxFnoV3LiveFeedService.DisplayQuote(
                "NSE_FO|61001", new BigDecimal("121.75"),
                null, null, null, null, null, null, null,
                null, null, now.minusSeconds(120), now.minusSeconds(1))));
        var store = new CustomerFnoLastTradeSnapshotStore(
            db, provider, true, Clock.fixed(now, ZoneOffset.UTC));
        store.captureFreshTrades();
        verifyNoInteractions(db);
    }
}
