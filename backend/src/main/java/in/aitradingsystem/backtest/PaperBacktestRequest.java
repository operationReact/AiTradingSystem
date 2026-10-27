package in.aitradingsystem.backtest;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PaperBacktestRequest(
    @Min(1) long instrumentId,
    @NotNull HistoricalCandleInterval interval,
    @NotNull LocalDate fromDate,
    @NotNull LocalDate toDate,
    @DecimalMin("10000") @DecimalMax("100000000") BigDecimal initialCapital
) {}