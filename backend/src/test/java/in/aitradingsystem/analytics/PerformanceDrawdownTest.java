package in.aitradingsystem.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerformanceDrawdownTest {
    @Test
    void observedPeakToTroughIncludesOpeningEquityAndKeepsNullForInsufficientEvidence() {
        assertThat(PerformanceAggregationService.sampledDrawdown(new BigDecimal("100"),
            List.of(new BigDecimal("120"), new BigDecimal("90"), new BigDecimal("110"))))
            .isEqualByComparingTo("25");
        assertThat(PerformanceAggregationService.sampledDrawdown(new BigDecimal("100"), List.of())).isNull();
    }
}
