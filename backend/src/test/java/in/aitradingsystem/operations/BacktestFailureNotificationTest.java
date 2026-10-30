package in.aitradingsystem.operations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import in.aitradingsystem.api.PaperBacktestController;
import in.aitradingsystem.backtest.*;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BacktestFailureNotificationTest {
    @Test
    void unexpectedExecutionFailureRaisesIncidentButExpectedDataRejectionDoesNot() {
        var backtests = mock(PaperBacktestService.class);
        var incidents = mock(OperationalIncidentRefreshService.class);
        var controller = new PaperBacktestController(backtests,mock(PaperBacktestValidationService.class),
            mock(WalkForwardValidationService.class),mock(BacktestApiService.class),incidents);
        var request = new PaperBacktestRequest(1,HistoricalCandleInterval.ONE_MINUTE,
            LocalDate.of(2026,1,1),LocalDate.of(2026,1,2),null);
        when(backtests.run(any())).thenThrow(new IllegalStateException("private provider error"));
        assertThatThrownBy(() -> controller.run(request)).isInstanceOf(IllegalStateException.class);
        verify(incidents).backtestFailed(1);
        clearInvocations(incidents);
        doThrow(new BacktestException("INSUFFICIENT_HISTORY","Expected data rejection")).when(backtests).run(any());
        assertThatThrownBy(() -> controller.run(request)).isInstanceOf(BacktestException.class);
        verifyNoInteractions(incidents);
    }
}
