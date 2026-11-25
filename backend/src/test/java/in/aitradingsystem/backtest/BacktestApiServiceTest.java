package in.aitradingsystem.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BacktestApiServiceTest {

    @Test
    void csvTextNeutralizesSpreadsheetFormulaPrefixes() {
        assertThat(BacktestApiService.safeText("=SUM(A1:A2)")).isEqualTo("'=SUM(A1:A2)");
        assertThat(BacktestApiService.safeText("+cmd")).isEqualTo("'+cmd");
        assertThat(BacktestApiService.safeText("-formula")).isEqualTo("'-formula");
        assertThat(BacktestApiService.safeText("@formula")).isEqualTo("'@formula");
    }

    @Test
    void csvTextEscapesQuotesCommasAndNewLines() {
        assertThat(BacktestApiService.safeText("target, hit")).isEqualTo("\"target, hit\"");
        assertThat(BacktestApiService.safeText("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(BacktestApiService.safeText("line1\nline2")).isEqualTo("\"line1\nline2\"");
    }
}
