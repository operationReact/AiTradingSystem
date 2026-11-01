package in.aitradingsystem.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.domain.ProductType;
import in.aitradingsystem.execution.OrderExecutionMode;
import in.aitradingsystem.execution.TradingExecutionAuthority;
import in.aitradingsystem.paper.AutomatedPaperPositionRepository.MonitoredPosition;
import in.aitradingsystem.risk.RiskPolicyConfigRepository;
import in.aitradingsystem.risk.RiskPolicySnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutomatedPaperPositionMonitorServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-01T05:00:00Z");

    @Test
    void manualModeDoesNotReadOrSubmitPositions() {
        Fixture f = fixture(OrderExecutionMode.MANUAL, false);

        var run = f.service.runOnce(NOW);

        assertFalse(run.automationEnabled());
        verify(f.positions, never()).openPositions(any());
        verify(f.trading, never()).placeOrder(any(), anyString());
    }

    @Test
    void emergencyStopPausesAutomatedPositionExecution() {
        Fixture f = fixture(OrderExecutionMode.AUTOMATED, true);

        var run = f.service.runOnce(NOW);

        assertTrue(run.automationEnabled());
        assertTrue(run.emergencyStopped());
        verify(f.positions, never()).openPositions(any());
        verify(f.trading, never()).placeOrder(any(), anyString());
    }

    @Test
    void stopLossTriggerSubmitsFullSellThroughNormalPaperService() {
        Fixture f = fixture(OrderExecutionMode.AUTOMATED, false);
        MonitoredPosition position = position("100.00", "101.00", "110.00", NOW.minusSeconds(5));
        when(f.positions.openPositions(NOW)).thenReturn(List.of(position));
        when(f.trading.placeOrder(any(OrderCommand.class), anyString()))
            .thenReturn(new PaperViews.OrderResult(null, null, null, false));

        var run = f.service.runOnce(NOW);

        assertEquals(1, run.triggered());
        assertEquals(1, run.submitted());
        verify(f.trading).placeOrder(any(OrderCommand.class), anyString());
        assertEquals("STOP_LOSS", run.attempts().getFirst().reason());
        assertTrue(run.attempts().getFirst().idempotencyKey().startsWith("auto-paper-exit:stop_loss:"));
    }

    @Test
    void targetTriggerSubmitsFullSellThroughNormalPaperService() {
        Fixture f = fixture(OrderExecutionMode.AUTOMATED, false);
        when(f.positions.openPositions(NOW)).thenReturn(List.of(position("111.00", "90.00", "110.00", NOW.minusSeconds(5))));
        when(f.trading.placeOrder(any(OrderCommand.class), anyString()))
            .thenReturn(new PaperViews.OrderResult(null, null, null, false));

        var run = f.service.runOnce(NOW);

        assertEquals(1, run.triggered());
        assertEquals("TARGET", run.attempts().getFirst().reason());
    }

    @Test
    void staleMarkNeverSubmitsProtectiveExit() {
        Fixture f = fixture(OrderExecutionMode.AUTOMATED, false);
        when(f.positions.openPositions(NOW)).thenReturn(List.of(position("99.00", "100.00", "110.00", NOW.minusSeconds(61))));

        var run = f.service.runOnce(NOW);

        assertEquals(1, run.staleSkipped());
        assertEquals(0, run.triggered());
        verify(f.trading, never()).placeOrder(any(), anyString());
    }

    private static Fixture fixture(OrderExecutionMode mode, boolean halted) {
        TradingExecutionAuthority authority = mock(TradingExecutionAuthority.class);
        AutomatedPaperPositionRepository positions = mock(AutomatedPaperPositionRepository.class);
        PaperTradingService trading = mock(PaperTradingService.class);
        RiskPolicyConfigRepository policies = mock(RiskPolicyConfigRepository.class);
        AuditService audit = mock(AuditService.class);
        when(authority.current()).thenReturn(new TradingExecutionAuthority.Snapshot("PAPER", mode));
        when(trading.isHalted()).thenReturn(halted);
        when(policies.requireActive()).thenReturn(policy(60));
        return new Fixture(positions, trading,
            new AutomatedPaperPositionMonitorService(authority, positions, trading, policies, audit));
    }

    private static MonitoredPosition position(String mark, String stop, String target, Instant markAt) {
        return new MonitoredPosition(
            101L, "NSE", "RELIANCE-EQ", ProductType.DELIVERY, 3,
            new BigDecimal(stop), new BigDecimal(target), new BigDecimal(mark), markAt, NOW.minusSeconds(120));
    }

    private static RiskPolicySnapshot policy(int marketAgeSeconds) {
        BigDecimal one = BigDecimal.ONE;
        return new RiskPolicySnapshot(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), 1,
            one, one, 10, one, new BigDecimal("1000000"), new BigDecimal("100"),
            new BigDecimal("100"), 100, 10, marketAgeSeconds,
            false, false, false, new BigDecimal("0.001"), new BigDecimal("0.001"), "test-v1");
    }

    private record Fixture(
        AutomatedPaperPositionRepository positions,
        PaperTradingService trading,
        AutomatedPaperPositionMonitorService service
    ) {}
}
