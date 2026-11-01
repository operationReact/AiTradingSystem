package in.aitradingsystem.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.audit.AuditService;
import in.aitradingsystem.execution.OrderExecutionMode;
import in.aitradingsystem.execution.TradingExecutionAuthority;
import in.aitradingsystem.strategy.StrategySignal;
import in.aitradingsystem.strategy.StrategySignalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutomatedPaperOrderServiceTest {

    @Test
    void manualModeNeverSubmitsOrders() {
        TradingExecutionAuthority authority = mock(TradingExecutionAuthority.class);
        StrategySignalRepository signals = mock(StrategySignalRepository.class);
        PaperOrderService orders = mock(PaperOrderService.class);
        AuditService audit = mock(AuditService.class);
        when(authority.current()).thenReturn(new TradingExecutionAuthority.Snapshot("PAPER", OrderExecutionMode.MANUAL));

        var service = new AutomatedPaperOrderService(authority, signals, orders, audit, 1, 20);
        var result = service.runOnce();

        assertFalse(result.automationEnabled());
        assertEquals(0, result.submitted());
        verify(signals, never()).recent(any(Integer.class));
        verify(orders, never()).placeOrder(any(), any());
    }

    @Test
    void automatedPaperSubmitsEligibleBuyCandidateThroughPaperOrderService() {
        TradingExecutionAuthority authority = mock(TradingExecutionAuthority.class);
        StrategySignalRepository signals = mock(StrategySignalRepository.class);
        PaperOrderService orders = mock(PaperOrderService.class);
        AuditService audit = mock(AuditService.class);
        when(authority.current()).thenReturn(new TradingExecutionAuthority.Snapshot("PAPER", OrderExecutionMode.AUTOMATED));
        StrategySignal signal = signal("11111111-1111-1111-1111-111111111111", "BUY");
        when(signals.recent(20)).thenReturn(List.of(signal));
        when(orders.placeOrder(any(OrderCommand.class), eq("auto-paper-signal:" + signal.id())))
            .thenReturn(new PaperViews.OrderResult(null, null, null, false));

        var service = new AutomatedPaperOrderService(authority, signals, orders, audit, 2, 20);
        var result = service.runOnce();

        assertTrue(result.automationEnabled());
        assertEquals(1, result.eligibleSignals());
        assertEquals(1, result.submitted());
        assertEquals(0, result.failed());
        verify(orders).placeOrder(any(OrderCommand.class), eq("auto-paper-signal:" + signal.id()));
    }

    @Test
    void watchCandidateIsIgnoredEvenInAutomatedMode() {
        TradingExecutionAuthority authority = mock(TradingExecutionAuthority.class);
        StrategySignalRepository signals = mock(StrategySignalRepository.class);
        PaperOrderService orders = mock(PaperOrderService.class);
        AuditService audit = mock(AuditService.class);
        when(authority.current()).thenReturn(new TradingExecutionAuthority.Snapshot("PAPER", OrderExecutionMode.AUTOMATED));
        when(signals.recent(20)).thenReturn(List.of(signal("22222222-2222-2222-2222-222222222222", "WATCH")));

        var service = new AutomatedPaperOrderService(authority, signals, orders, audit, 1, 20);
        var result = service.runOnce();

        assertEquals(0, result.eligibleSignals());
        assertEquals(0, result.submitted());
        verify(orders, never()).placeOrder(any(), any());
    }

    private static StrategySignal signal(String id, String action) {
        Instant now = Instant.parse("2026-10-30T05:00:00Z");
        return new StrategySignal(
            id, 101L, "RELIANCE-EQ", "NSE", "FIVE_MINUTE", action, "SMA_TREND_VOLUME_V1",
            new BigDecimal("100.00"), new BigDecimal("99.00"), new BigDecimal("102.00"),
            new BigDecimal("100.10"), new BigDecimal("99.90"), new BigDecimal("1000"),
            new BigDecimal("1500"), 0.85, List.of("TEST"), "TEST", now, now, "CANDIDATE_NOT_EXECUTABLE");
    }
}
