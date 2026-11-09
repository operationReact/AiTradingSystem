package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.scanner.ResearchScanRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnerLiveFeedWarmerTest {
    private static final Instant NOW = Instant.parse("2026-11-09T05:00:00Z");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000f00d");
    private static final Instrument NSE = new Instrument(42L, "NSE_CM", "NSE",
        "2885", "TEST-EQ", "Test", "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());

    @Test
    void closedMarketDoesNothingAndNeverLogsIn() {
        var liveQuotes = mock(CustomerAngelOneLiveQuoteService.class);
        var sessions = mock(CustomerAngelOneSessionService.class);
        var ssm = mock(OwnerAngelOneSsmService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var scans = mock(ResearchScanRepository.class);
        when(calendar.isInsideCashSession(NOW)).thenReturn(false);

        new OwnerLiveFeedWarmer(liveQuotes, sessions, ssm, calendar, scans,
            Clock.fixed(NOW, ZoneOffset.UTC), OWNER, 500).warm();

        verifyNoInteractions(liveQuotes, sessions, ssm, scans);
    }

    @Test
    void openMarketLogsInWhenNoSessionAndSnapshotsCappedUniverse() {
        var liveQuotes = mock(CustomerAngelOneLiveQuoteService.class);
        var sessions = mock(CustomerAngelOneSessionService.class);
        var ssm = mock(OwnerAngelOneSsmService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var scans = mock(ResearchScanRepository.class);
        when(calendar.isInsideCashSession(NOW)).thenReturn(true);
        when(ssm.available()).thenReturn(true);
        when(scans.activeCashEquityUniverse(eq(500))).thenReturn(List.of(NSE));

        new OwnerLiveFeedWarmer(liveQuotes, sessions, ssm, calendar, scans,
            Clock.fixed(NOW, ZoneOffset.UTC), OWNER, 500).warm();

        verify(ssm).connect(OWNER);
        verify(scans).activeCashEquityUniverse(500);
        verify(liveQuotes).snapshot(OWNER, List.of(NSE));
    }

    @Test
    void warmUpFailureIsContainedSoSchedulerKeepsRunning() {
        var liveQuotes = mock(CustomerAngelOneLiveQuoteService.class);
        var sessions = mock(CustomerAngelOneSessionService.class);
        var ssm = mock(OwnerAngelOneSsmService.class);
        var calendar = mock(MarketSessionCalendar.class);
        var scans = mock(ResearchScanRepository.class);
        when(calendar.isInsideCashSession(NOW)).thenReturn(true);
        when(ssm.available()).thenReturn(false);
        when(scans.activeCashEquityUniverse(anyInt())).thenThrow(new IllegalStateException("db down"));

        var warmer = new OwnerLiveFeedWarmer(liveQuotes, sessions, ssm, calendar, scans,
            Clock.fixed(NOW, ZoneOffset.UTC), OWNER, 500);
        assertThatCode(warmer::warm).doesNotThrowAnyException();
        verify(liveQuotes, never()).snapshot(any(), anyList());
    }

    @Test
    void rejectsNonPositiveUniverseLimit() {
        assertThatThrownBy(() -> new OwnerLiveFeedWarmer(mock(CustomerAngelOneLiveQuoteService.class),
            mock(CustomerAngelOneSessionService.class), mock(OwnerAngelOneSsmService.class),
            mock(MarketSessionCalendar.class), mock(ResearchScanRepository.class),
            Clock.fixed(NOW, ZoneOffset.UTC), OWNER, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
