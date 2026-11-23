package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

/** Fixed-clock scheduler negative gates. No PostgreSQL or provider network. */
class CustomerMindFnoPaperAutomationServiceTest {
    private static final Instant SATURDAY=Instant.parse("2026-11-14T04:00:00Z");
    private static final Instant SESSION=Instant.parse("2026-11-16T04:00:00Z");
    private final UUID owner=UUID.randomUUID(),room=UUID.randomUUID();
    private final JdbcClient jdbc=mock(JdbcClient.class,RETURNS_DEEP_STUBS);
    private final CustomerPaperFnoCapabilityController capability=
        mock(CustomerPaperFnoCapabilityController.class);
    private final CustomerMindFnoPaperCycleService cycles=
        mock(CustomerMindFnoPaperCycleService.class);
    private final MarketSessionCalendar calendar=mock(MarketSessionCalendar.class);

    private CustomerMindFnoPaperAutomationService scheduler(Instant now) {
        return new CustomerMindFnoPaperAutomationService(jdbc,capability,cycles,
            Clock.fixed(now,ZoneOffset.UTC),calendar);
    }

    @Test
    void schedulerDoesNotClaimOrEvaluateWhenNseFnoSessionClosed() {
        when(calendar.isInsideFnoSession(SATURDAY)).thenReturn(false);
        scheduler(SATURDAY).poll();
        verify(calendar).isInsideFnoSession(SATURDAY);
        verifyNoInteractions(jdbc,capability,cycles);
    }

    @Test
    void unfundedOrUnownedRoomCannotBeEnabled() {
        when(jdbc.sql(anyString()).param(eq("room"),eq(room))
            .param(eq("owner"),eq(owner)).query(Boolean.class).single())
            .thenReturn(false);
        assertThatThrownBy(()->scheduler(SESSION).setEnabled(owner,room,true))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e->org.assertj.core.api.Assertions.assertThat(
                ((ResponseStatusException)e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(capability,cycles);
        verify(jdbc,never()).sql(contains("INSERT INTO mind_fno_paper_automation"));
    }

    @Test
    void missingSourceOrUsageRightsCannotArmVirtualWorker() {
        when(jdbc.sql(anyString()).param(eq("room"),eq(room))
            .param(eq("owner"),eq(owner)).query(Boolean.class).single())
            .thenReturn(true);
        when(capability.internalCapability(owner,room)).thenReturn(
            new CustomerPaperFnoCapabilityController.Capability(
                "PAPER_USE_RIGHTS_NOT_VERIFIED",true,false,false,
                List.of("PAPER_USE_RIGHTS_NOT_VERIFIED")));
        assertThatThrownBy(()->scheduler(SESSION).setEnabled(owner,room,true))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e->org.assertj.core.api.Assertions.assertThat(
                ((ResponseStatusException)e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(jdbc,never()).sql(contains("INSERT INTO mind_fno_paper_automation"));
        verifyNoInteractions(cycles);
    }

    @Test
    void noCustomerContextCannotEnableScheduler() {
        assertThatThrownBy(()->scheduler(SESSION).setEnabled(null,room,true))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->scheduler(SESSION).setEnabled(owner,null,true))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc,capability,cycles);
    }
}
